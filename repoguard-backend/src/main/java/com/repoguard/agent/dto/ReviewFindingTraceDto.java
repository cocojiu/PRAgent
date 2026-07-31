package com.repoguard.agent.dto;

public record ReviewFindingTraceDto(
    String detectorVersion,
    Long ruleConfigVersion,
    String promptVersion,
    String contextVersion,
    String schemaVersion,
    String verifierVersion,
    String aggregationVersion,
    Long policyVersion,
    String llmProvider,
    String llmModel,
    String originalSeverity,
    String effectiveSeverity,
    String originalConfidence,
    String effectiveConfidence,
    String downgradeReason,
    String blockReason,
    String anchorType
) {

    public static ReviewFindingTraceDto legacy(String severity, String confidence, Boolean blocking) {
        return new ReviewFindingTraceDto(
            "legacy-detector-v1",
            1L,
            "not-applicable",
            "not-applicable",
            "not-applicable",
            "not-applicable",
            "server-risk-v2",
            1L,
            null,
            null,
            severity,
            severity,
            confidence,
            confidence,
            "",
            Boolean.TRUE.equals(blocking) ? "legacy_finding" : "",
            "NONE"
        );
    }
}
