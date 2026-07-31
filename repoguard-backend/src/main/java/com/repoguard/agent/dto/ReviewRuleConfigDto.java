package com.repoguard.agent.dto;

public record ReviewRuleConfigDto(
    String id,
    String name,
    String scope,
    String applicableLanguages,
    String filePatterns,
    String severity,
    String status,
    long hitCount,
    String confidence,
    String updatedAt,
    String description,
    String positiveExample,
    String falsePositiveGuidance,
    String enforcementMode,
    String detectorVersion,
    long configVersion,
    long policyVersion,
    ReviewRuleQualityGateDto qualityGate
) {
    public ReviewRuleConfigDto(
        String id,
        String name,
        String scope,
        String applicableLanguages,
        String filePatterns,
        String severity,
        String status,
        long hitCount,
        String confidence,
        String updatedAt,
        String description,
        String positiveExample,
        String falsePositiveGuidance,
        String enforcementMode
    ) {
        this(
            id,
            name,
            scope,
            applicableLanguages,
            filePatterns,
            severity,
            status,
            hitCount,
            confidence,
            updatedAt,
            description,
            positiveExample,
            falsePositiveGuidance,
            enforcementMode,
            id == null ? "legacy-detector-v1" : id.toLowerCase(java.util.Locale.ROOT) + "-detector-v2",
            1,
            1,
            emptyQualityGate()
        );
    }

    private static ReviewRuleQualityGateDto emptyQualityGate() {
        return new ReviewRuleQualityGateDto(
            0,
            0,
            java.math.BigDecimal.ZERO,
            java.math.BigDecimal.ZERO,
            java.math.BigDecimal.ZERO,
            java.math.BigDecimal.ZERO,
            false,
            false,
            "INSUFFICIENT_SAMPLE",
            java.util.List.of("labeled_high_risk_samples_below_30")
        );
    }

    public ReviewRuleConfigDto(
        String id,
        String name,
        String scope,
        String applicableLanguages,
        String filePatterns,
        String severity,
        String status,
        long hitCount,
        String confidence,
        String updatedAt,
        String description
    ) {
        this(
            id,
            name,
            scope,
            applicableLanguages,
            filePatterns,
            severity,
            status,
            hitCount,
            confidence,
            updatedAt,
            description,
            "",
            "",
            "comment"
        );
    }

    public ReviewRuleConfigDto(
        String id,
        String name,
        String scope,
        String severity,
        String status,
        long hitCount,
        String confidence,
        String updatedAt,
        String description
    ) {
        this(
            id,
            name,
            scope,
            "",
            "",
            severity,
            status,
            hitCount,
            confidence,
            updatedAt,
            description,
            "",
            "",
            "comment"
        );
    }

    public ReviewRuleConfigDto(
        String id,
        String name,
        String scope,
        String applicableLanguages,
        String filePatterns,
        String severity,
        String status,
        long hitCount,
        String confidence,
        String updatedAt,
        String description,
        String positiveExample,
        String falsePositiveGuidance
    ) {
        this(
            id,
            name,
            scope,
            applicableLanguages,
            filePatterns,
            severity,
            status,
            hitCount,
            confidence,
            updatedAt,
            description,
            positiveExample,
            falsePositiveGuidance,
            "comment"
        );
    }
}
