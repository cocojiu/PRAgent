package com.repoguard.agent.dto;

import java.math.BigDecimal;

/** One finding's cross-attempt state, including the evidence used to match it. */
public record ReviewFindingComparisonDto(
    Long id,
    Long attemptId,
    Long baselineFindingId,
    String status,
    String findingFingerprint,
    BigDecimal confidence,
    String reason,
    String comparisonVersion,
    String category,
    String severity,
    String source,
    String ruleId,
    String file,
    Integer line,
    String message,
    String recommendation,
    Boolean blocking,
    String feedbackStatus
) {
}
