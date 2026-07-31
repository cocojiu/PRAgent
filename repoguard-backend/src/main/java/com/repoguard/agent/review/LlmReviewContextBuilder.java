package com.repoguard.agent.review;

import com.repoguard.agent.config.LlmReviewContextProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class LlmReviewContextBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmReviewContextBuilder.class);

    private final ReviewRuleProvider ruleProvider;
    private final LlmReviewContextProperties properties;
    private final DiffRiskClassifier riskClassifier;
    private final LlmSourceContextSlicer sourceSlicer;

    LlmReviewContextBuilder() {
        this(null, new LlmReviewContextProperties(), new DiffRiskClassifier());
    }

    @Autowired
    LlmReviewContextBuilder(
        ReviewRuleProvider ruleProvider,
        LlmReviewContextProperties properties,
        DiffRiskClassifier riskClassifier
    ) {
        this.ruleProvider = ruleProvider;
        this.properties = Objects.requireNonNull(properties, "properties");
        this.riskClassifier = Objects.requireNonNull(riskClassifier, "riskClassifier");
        this.sourceSlicer = new LlmSourceContextSlicer(properties);
    }

    LlmReviewContext build(PullRequestDiff diff) {
        List<PullRequestChangedFile> files = diff == null || diff.files() == null ? List.of() : diff.files();
        List<PullRequestChangedFile> prioritized = files.stream()
            .sorted(Comparator
                .comparingInt(riskClassifier::priority)
                .thenComparing(PullRequestChangedFile::filename, Comparator.nullsLast(String::compareTo)))
            .toList();
        List<LlmContextSlice> slices = new ArrayList<>();
        List<LlmReviewContext.ContextLimitation> limitations = new ArrayList<>();
        int retainedChars = 0;
        boolean truncated = false;
        for (PullRequestChangedFile file : prioritized) {
            ChangedFileContext context = file.context();
            if (context != null
                && context.available()
                && StringUtils.hasText(diff == null ? null : diff.headSha())
                && !diff.headSha().equals(context.headSha())) {
                limitations.add(new LlmReviewContext.ContextLimitation(
                    file.filename(),
                    "UNAVAILABLE",
                    "head_sha_mismatch"
                ));
                continue;
            }
            if (context == null || !context.available()) {
                if (context == null || context.status() != ChangedFileContext.Status.EXCLUDED
                    && context.status() != ChangedFileContext.Status.DELETED) {
                    limitations.add(new LlmReviewContext.ContextLimitation(
                        file.filename(),
                        context == null ? "UNAVAILABLE" : context.status().name(),
                        context == null ? "missing_context" : context.reason()
                    ));
                }
                continue;
            }
            LlmContextSlice slice = sourceSlicer.slice(file, riskClassifier.priority(file));
            if (slice == null) {
                limitations.add(new LlmReviewContext.ContextLimitation(
                    file.filename(),
                    "UNAVAILABLE",
                    "empty_text_context"
                ));
                continue;
            }
            int nextChars = slice.numberedContent().length();
            if (retainedChars + nextChars > properties.getMaxTotalChars()) {
                truncated = true;
                continue;
            }
            retainedChars += nextChars;
            slices.add(slice);
        }
        return new LlmReviewContext(
            slices,
            rulePolicyContext(),
            limitations,
            truncated,
            properties.getMaxTotalChars(),
            properties.getMaxRelatedFiles()
        );
    }

    private String rulePolicyContext() {
        if (ruleProvider == null) {
            return "";
        }
        Map<String, ReviewRuleSettings> rules;
        try {
            rules = ruleProvider.getRulesById();
        } catch (RuntimeException ex) {
            LOGGER.warn(
                "Review rule policy context unavailable operation=llm_context result=degraded exceptionType={}",
                ex.getClass().getName()
            );
            return "rule_policy_unavailable";
        }
        if (rules == null || rules.isEmpty()) {
            return "no_enabled_rules";
        }
        return rules.values().stream()
            .filter(Objects::nonNull)
            .filter(rule -> !rule.disabled())
            .sorted(Comparator.comparing(ReviewRuleSettings::id))
            .limit(properties.getMaxRulePolicies())
            .map(this::formatRule)
            .reduce((first, second) -> first + "\n" + second)
            .orElse("no_enabled_rules");
    }

    private String formatRule(ReviewRuleSettings rule) {
        return String.join(
            " | ",
            rule.id(),
            "severity=" + rule.severity(),
            "confidence=" + rule.confidence(),
            "mode=" + rule.enforcementMode().name(),
            "description=" + bounded(rule.description()),
            "positive=" + bounded(rule.positiveExample()),
            "falsePositive=" + bounded(rule.falsePositiveGuidance())
        );
    }

    private String bounded(String value) {
        if (!StringUtils.hasText(value)) {
            return "-";
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.length() <= properties.getMaxRuleTextChars()
            ? normalized
            : normalized.substring(0, properties.getMaxRuleTextChars());
    }
}
