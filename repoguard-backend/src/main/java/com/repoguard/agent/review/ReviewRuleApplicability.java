package com.repoguard.agent.review;

import com.repoguard.agent.review.ReviewRuleSettings;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.util.StringUtils;

final class ReviewRuleApplicability {

    private static final Map<String, List<String>> FILE_PATTERNS = new ConcurrentHashMap<>();

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
        String normalizedFilePath = normalizePath(filePath);
        return parsedPatterns(rule.filePatterns()).stream()
            .anyMatch(pattern -> matchesNormalizedPathPattern(normalizedFilePath, pattern, false));
    }

    static Set<String> applicableRuleIds(
        String filePath,
        Map<String, ReviewRuleSettings> configuredRules
    ) {
        if (configuredRules == null || configuredRules.isEmpty()) {
            return Set.of();
        }
        return configuredRules.keySet().stream()
            .filter(ruleId -> isApplicable(ruleId, filePath, configuredRules))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    static String normalizePath(String value) {
        return value == null ? "" : value.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    static boolean matchesPathPattern(String filePath, String pattern) {
        return matchesNormalizedPathPattern(normalizePath(filePath), normalizePath(pattern), false);
    }

    static boolean matchesAnchoredPathPattern(String filePath, String pattern) {
        return matchesNormalizedPathPattern(normalizePath(filePath), normalizePath(pattern), true);
    }

    private static List<String> parsedPatterns(String filePatterns) {
        return FILE_PATTERNS.computeIfAbsent(filePatterns, value -> List.of(value.split("[,\\n]")).stream()
            .map(String::trim)
            .filter(StringUtils::hasText)
            .map(ReviewRuleApplicability::normalizePath)
            .toList());
    }

    private static boolean matchesNormalizedPathPattern(
        String normalizedFilePath,
        String pattern,
        boolean anchored
    ) {
        String normalizedPattern = normalizePath(pattern);
        if ("*".equals(normalizedPattern)) {
            return true;
        }
        String effectivePattern = anchored || normalizedPattern.startsWith("*")
            ? normalizedPattern
            : "*" + normalizedPattern;
        return globMatches(normalizedFilePath, effectivePattern);
    }

    /** Matches the supported glob syntax in linear time without regex backtracking. */
    private static boolean globMatches(String value, String pattern) {
        int valueIndex = 0;
        int patternIndex = 0;
        int lastStar = -1;
        int retryValueIndex = -1;
        while (valueIndex < value.length()) {
            if (patternIndex < pattern.length()
                && (pattern.charAt(patternIndex) == '?' || pattern.charAt(patternIndex) == value.charAt(valueIndex))) {
                valueIndex++;
                patternIndex++;
            } else if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
                lastStar = patternIndex++;
                retryValueIndex = valueIndex;
            } else if (lastStar >= 0) {
                patternIndex = lastStar + 1;
                valueIndex = ++retryValueIndex;
            } else {
                return false;
            }
        }
        while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
            patternIndex++;
        }
        return patternIndex == pattern.length();
    }
}
