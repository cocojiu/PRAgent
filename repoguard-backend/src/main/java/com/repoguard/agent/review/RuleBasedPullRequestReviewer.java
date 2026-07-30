package com.repoguard.agent.review;

import com.repoguard.agent.review.ReviewRuleProvider;
import com.repoguard.agent.review.ReviewRuleSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RuleBasedPullRequestReviewer {

    private final ReviewRuleProvider reviewRuleProvider;
    private final List<ReviewRule> lineRules;
    private final List<PullRequestReviewRule> pullRequestRules;
    private final FindingPolicyResolver findingPolicyResolver;
    private final ReviewFindingFactory reviewFindingFactory;
    private final ReviewFindingSemanticDeduplicator findingDeduplicator;
    private final ServerRiskAggregator riskAggregator;

    @Autowired
    public RuleBasedPullRequestReviewer(
        ReviewRuleProvider reviewRuleProvider,
        ReviewRuleRegistry reviewRuleRegistry,
        FindingPolicyResolver findingPolicyResolver,
        ReviewFindingFactory reviewFindingFactory,
        ReviewFindingSemanticDeduplicator findingDeduplicator,
        ServerRiskAggregator riskAggregator
    ) {
        this.reviewRuleProvider = Objects.requireNonNull(reviewRuleProvider, "reviewRuleProvider");
        ReviewRuleRegistry registry = Objects.requireNonNull(reviewRuleRegistry, "reviewRuleRegistry");
        this.lineRules = registry.lineRules();
        this.pullRequestRules = registry.pullRequestRules();
        this.findingPolicyResolver = Objects.requireNonNull(findingPolicyResolver, "findingPolicyResolver");
        this.reviewFindingFactory = Objects.requireNonNull(reviewFindingFactory, "reviewFindingFactory");
        this.findingDeduplicator = Objects.requireNonNull(findingDeduplicator, "findingDeduplicator");
        this.riskAggregator = Objects.requireNonNull(riskAggregator, "riskAggregator");
    }

    RuleBasedPullRequestReviewer(
        ReviewRuleProvider reviewRuleProvider,
        List<ReviewRule> lineRules,
        List<PullRequestReviewRule> pullRequestRules
    ) {
        this(
            reviewRuleProvider,
            new ReviewRuleRegistry(lineRules, pullRequestRules),
            new FindingPolicyResolver(),
            new ReviewFindingFactory(),
            new ReviewFindingSemanticDeduplicator(),
            new ServerRiskAggregator()
        );
    }

    public ReviewResult review(PullRequestDiff diff) {
        Map<String, ReviewRuleSettings> loadedRules = reviewRuleProvider.getRulesById();
        Map<String, ReviewRuleSettings> configuredRules = loadedRules == null ? Map.of() : loadedRules;
        List<RuleMatch> matches = new ArrayList<>();
        List<PullRequestChangedFile> files = diff.files() == null ? List.of() : diff.files();
        for (PullRequestChangedFile file : files) {
            String patch = file.patch();
            if (patch == null || patch.isBlank()) {
                continue;
            }
            scanPatch(file.filename(), patch, configuredRules, matches);
        }
        scanPullRequestLevelRules(diff, configuredRules, matches);
        List<ReviewFindingResult> findings = matches.stream()
            .map(match -> resolveFinding(match, configuredRules))
            .filter(Objects::nonNull)
            .toList();
        List<ReviewFindingResult> uniqueFindings = findingDeduplicator.deduplicate(findings);
        return ReviewResult.completed(riskAggregator.aggregate(uniqueFindings), uniqueFindings);
    }

    private void scanPullRequestLevelRules(
        PullRequestDiff diff,
        Map<String, ReviewRuleSettings> configuredRules,
        List<RuleMatch> matches
    ) {
        for (PullRequestReviewRule rule : pullRequestRules) {
            matches.addAll(rule.evaluate(diff, configuredRules));
        }
    }

    private void scanPatch(
        String filePath,
        String patch,
        Map<String, ReviewRuleSettings> configuredRules,
        List<RuleMatch> matches
    ) {
        String[] lines = patch.split("\\R");
        int currentLine = 0;
        boolean hasAuthorizationGuard = patchHasAuthorizationGuard(lines);
        for (String line : lines) {
            if (line.startsWith("@@")) {
                currentLine = parseNewFileStart(line);
                continue;
            }
            if (line.startsWith("+") && !line.startsWith("+++")) {
                String added = line.substring(1);
                evaluateLineRules(filePath, currentLine, added, configuredRules, matches, hasAuthorizationGuard);
                currentLine++;
            } else if (!line.startsWith("-")) {
                currentLine++;
            }
        }
    }

    private void evaluateLineRules(
        String filePath,
        int lineNumber,
        String line,
        Map<String, ReviewRuleSettings> configuredRules,
        List<RuleMatch> matches,
        boolean hasAuthorizationGuard
    ) {
        ReviewRuleLineContext context = new ReviewRuleLineContext(
            filePath,
            lineNumber,
            line,
            line.trim(),
            configuredRules,
            hasAuthorizationGuard
        );
        for (ReviewRule rule : lineRules) {
            rule.evaluate(context).ifPresent(matches::add);
        }
    }

    private boolean patchHasAuthorizationGuard(String[] lines) {
        for (String line : lines) {
            if (!line.startsWith("-") && hasAuthorizationGuard(line.replaceFirst("^[+ ]", "").trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAuthorizationGuard(String trimmed) {
        return trimmed.contains("@RequireRole")
            || trimmed.contains("@RequirePermission")
            || trimmed.contains("@PreAuthorize")
            || trimmed.contains("@Secured")
            || trimmed.contains("@RolesAllowed");
    }

    private int parseNewFileStart(String hunkHeader) {
        int marker = hunkHeader.indexOf('+');
        if (marker < 0) {
            return 0;
        }
        int end = hunkHeader.indexOf(' ', marker);
        String range = (end < 0 ? hunkHeader.substring(marker + 1) : hunkHeader.substring(marker + 1, end)).trim();
        int comma = range.indexOf(',');
        String start = comma < 0 ? range : range.substring(0, comma);
        try {
            return Integer.parseInt(start);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private ReviewFindingResult resolveFinding(
        RuleMatch match,
        Map<String, ReviewRuleSettings> configuredRules
    ) {
        ReviewRuleSettings settings = configuredRules.get(match.ruleId());
        if (settings == null || settings.disabled()) {
            return null;
        }
        EffectiveFinding effectiveFinding = findingPolicyResolver.resolve(
            match,
            settings,
            EvidenceValidation.forRuleMatch(match)
        );
        return reviewFindingFactory.finding(effectiveFinding);
    }
}
