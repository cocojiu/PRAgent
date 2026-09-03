package com.repoguard.agent.dto;

import java.math.BigDecimal;

public record ReviewAttemptFindingDto(
    Long id,
    String category,
    String severity,
    String source,
    String ruleId,
    String file,
    Integer line,
    String message,
    String recommendation,
    String confidence,
    Boolean blocking,
    String feedbackStatus,
    String promptVersion,
    String contextVersion,
    String schemaVersion,
    String verifierVersion,
    String aggregationVersion,
    String findingFingerprint,
    Long previousFindingId,
    String comparisonStatus,
    BigDecimal comparisonConfidence,
    String comparisonReason,
    String comparisonVersion,
    Long comparisonAttemptId
) {
}
