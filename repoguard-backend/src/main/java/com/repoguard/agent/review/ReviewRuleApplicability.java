package com.repoguard.agent.review;

import com.repoguard.agent.review.ReviewRuleSettings;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

final class ReviewRuleApplicability {

    private ReviewRuleApplicability() {
    }

    static boolean isApplicable(String ruleId, String filePath, Map<String, ReviewRuleSettings> configuredRules) {
        Map<String, ReviewRuleSettings> rules = configuredRules == null ? Map.of() : configuredRules;
        ReviewRuleSettings rule = rules.get(ruleId);
        if (rule == null) {
            return false;
        }
        if (rule.disabled()) {
            return false;
        }
        if (!rule.hasFilePatterns()) {
            return true;
        }
        return List.of(rule.filePatterns().split("[,\\n]")).stream()
            .map(String::trim)
            .filter(StringUtils::hasText)
            .anyMatch(pattern -> matchesPathPattern(filePath, pattern));
    }

    static String normalizePath(String value) {
        return value == null ? "" : value.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    static boolean matchesPathPattern(String filePath, String pattern) {
        String normalizedFilePath = normalizePath(filePath);
        String normalizedPattern = normalizePath(pattern);
        if ("*".equals(normalizedPattern)) {
            return true;
        }
        String regex = globToRegex(normalizedPattern);
        return normalizedFilePath.matches(".*" + regex);
    }

    static boolean matchesAnchoredPathPattern(String filePath, String pattern) {
        String normalizedFilePath = normalizePath(filePath);
        String normalizedPattern = normalizePath(pattern);
        return normalizedFilePath.matches(globToRegex(normalizedPattern));
    }

    private static String globToRegex(String pattern) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (int index = 0; index < pattern.length(); index++) {
            char current = pattern.charAt(index);
            if (current == '*' || current == '?') {
                appendQuotedLiteral(regex, literal);
                regex.append(current == '*' ? ".*" : ".");
            } else {
                literal.append(current);
            }
        }
        appendQuotedLiteral(regex, literal);
        return regex.toString();
    }

    private static void appendQuotedLiteral(StringBuilder regex, StringBuilder literal) {
        if (literal.isEmpty()) {
            return;
        }
        regex.append(Pattern.quote(literal.toString()));
        literal.setLength(0);
    }
}
