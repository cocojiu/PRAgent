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
    String enforcementMode
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
