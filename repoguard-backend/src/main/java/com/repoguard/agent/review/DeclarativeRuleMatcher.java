package com.repoguard.agent.review;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Executes only the validated, line-oriented declarative DSL. */
@Component
public class DeclarativeRuleMatcher {

    private final DeclarativeRulePolicy policy;

    public DeclarativeRuleMatcher(DeclarativeRulePolicy policy) {
        this.policy = policy;
    }

    public List<RuleMatch> matches(
        ReviewRuleSettings settings,
        String filePath,
        int lineNumber,
        String line
    ) {
        if (settings == null || !settings.isDeclarative() || settings.disabled()
            || !StringUtils.hasText(line) || excluded(filePath, settings.exceptionPatterns())) {
            return List.of();
        }
        policy.validate(settings.detectorType(), settings.matcherExpression(), settings.exceptionPatterns());
        boolean matched = switch (settings.detectorType()) {
            case DeclarativeRulePolicy.REGEX -> Pattern.compile(settings.matcherExpression()).matcher(line).find();
            case DeclarativeRulePolicy.AST -> astMatches(settings.matcherExpression(), line);
            default -> false;
        };
        if (!matched) {
            return List.of();
        }
        String recommendation = StringUtils.hasText(settings.falsePositiveGuidance())
            ? settings.falsePositiveGuidance()
            : settings.positiveExample();
        String message = StringUtils.hasText(settings.description())
            ? settings.description()
            : "Declarative rule matched: " + settings.id();
        return List.of(new RuleMatch(
            settings.id(),
            filePath,
            lineNumber > 0 ? lineNumber : null,
            message,
            recommendation,
            line.trim(),
            "declarative_rule_match",
            "DECLARATIVE_RULE",
            true
        ));
    }

    private boolean astMatches(String query, String line) {
        String normalized = query.trim();
        int separator = normalized.indexOf(':');
        if (separator < 0) {
            return false;
        }
        String kind = normalized.substring(0, separator).toLowerCase(Locale.ROOT);
        String name = normalized.substring(separator + 1);
        if ("call".equals(kind)) {
            return Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\(").matcher(line).find();
        }
        return Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(line).find();
    }

    private boolean excluded(String filePath, String exceptionPatterns) {
        if (!StringUtils.hasText(exceptionPatterns)) {
            return false;
        }
        return java.util.Arrays.stream(exceptionPatterns.split("[,\\n]"))
            .map(String::trim)
            .filter(StringUtils::hasText)
            .anyMatch(pattern -> ReviewRuleApplicability.matchesPathPattern(filePath, pattern));
    }
}
