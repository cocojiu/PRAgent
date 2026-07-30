package com.repoguard.agent.review;

import java.util.Locale;
import java.util.Set;
import org.springframework.util.StringUtils;

public record ReviewRuleSettings(
    String id,
    String status,
    String filePatterns,
    String severity,
    int confidence,
    EnforcementMode enforcementMode,
    String positiveExample,
    String falsePositiveGuidance
) {

    private static final Set<String> STATUSES = Set.of("ENABLED", "DISABLED");
    private static final Set<String> SEVERITIES = Set.of("INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL");

    public ReviewRuleSettings {
        id = requireText(id, "id").toUpperCase(Locale.ROOT);
        status = normalize(status, "status", STATUSES);
        filePatterns = filePatterns == null ? "" : filePatterns.trim();
        severity = normalize(severity, "severity", SEVERITIES);
        if (confidence < 0 || confidence > 100) {
            throw new IllegalArgumentException("Review rule confidence must be between 0 and 100");
        }
        if (enforcementMode == null) {
            throw new IllegalArgumentException("Review rule enforcementMode must not be null");
        }
        positiveExample = positiveExample == null ? "" : positiveExample.trim();
        falsePositiveGuidance = falsePositiveGuidance == null ? "" : falsePositiveGuidance.trim();
    }

    public ReviewRuleSettings(String id, String status, String filePatterns) {
        this(id, status, filePatterns, "MEDIUM", 90, EnforcementMode.COMMENT, "", "");
    }

    public boolean disabled() {
        return "DISABLED".equals(status);
    }

    public boolean hasFilePatterns() {
        return StringUtils.hasText(filePatterns);
    }

    public String confidenceLabel() {
        if (confidence >= 80) {
            return "HIGH";
        }
        if (confidence >= 55) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private static String normalize(String value, String field, Set<String> allowed) {
        String normalized = requireText(value, field).toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported review rule " + field + ": " + value);
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Review rule " + field + " must not be blank");
        }
        return value.trim();
    }
}
