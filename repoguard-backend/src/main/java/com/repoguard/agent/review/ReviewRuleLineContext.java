package com.repoguard.agent.review;

import com.repoguard.agent.config.ReviewRuleSettings;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.util.StringUtils;

record ReviewRuleLineContext(
    String filePath,
    int lineNumber,
    String line,
    String trimmedLine,
    Map<String, ReviewRuleSettings> configuredRules
) {

    boolean isApplicable(String ruleId) {
        Map<String, ReviewRuleSettings> rules = configuredRules == null ? Map.of() : configuredRules;
        ReviewRuleSettings rule = rules.get(ruleId);
        if (rule == null) {
            return true;
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
            .anyMatch(pattern -> matchesPattern(filePath, pattern));
    }

    String normalizePath(String value) {
        return value == null ? "" : value.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private boolean matchesPattern(String candidateFilePath, String pattern) {
        String normalizedFilePath = normalizePath(candidateFilePath);
        String normalizedPattern = normalizePath(pattern);
        if ("*".equals(normalizedPattern)) {
            return true;
        }
        String regex = normalizedPattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".");
        return normalizedFilePath.matches(".*" + regex);
    }
}
