package com.repoguard.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ReviewRuleConfigRequest(
    @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9_-]+$") String id,
    @NotBlank @Size(max = 128) String name,
    @NotBlank @Size(max = 255) String scope,
    @Size(max = 255) String applicableLanguages,
    @Size(max = 512) String filePatterns,
    @NotBlank @Pattern(regexp = "(?i)critical|high|medium|low|info") String severity,
    @NotBlank @Pattern(regexp = "(?i)enabled|disabled") String status,
    @Min(0) @Max(100) Integer confidence,
    @NotBlank @Size(max = 1024) String description,
    @Size(max = 1024) String positiveExample,
    @Size(max = 1024) String falsePositiveGuidance,
    @NotBlank @Pattern(regexp = "(?i)observe|comment|block") String enforcementMode,
    @Pattern(regexp = "(?i)builtin|regex|ast") @Size(max = 16) String detectorType,
    @Size(max = 1024) String matcherExpression,
    @Size(max = 1024) String exceptionPatterns
) {
    public ReviewRuleConfigRequest(
        String id,
        String name,
        String scope,
        String applicableLanguages,
        String filePatterns,
        String severity,
        String status,
        Integer confidence,
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
            confidence,
            description,
            positiveExample,
            falsePositiveGuidance,
            enforcementMode,
            null,
            null,
            null
        );
    }

    public ReviewRuleConfigRequest(
        String id,
        String name,
        String scope,
        String applicableLanguages,
        String filePatterns,
        String severity,
        String status,
        Integer confidence,
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
            confidence,
            description,
            positiveExample,
            falsePositiveGuidance,
            "COMMENT",
            null,
            null,
            null
        );
    }
}
