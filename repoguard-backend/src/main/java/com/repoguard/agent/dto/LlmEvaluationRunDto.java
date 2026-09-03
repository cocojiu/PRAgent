package com.repoguard.agent.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Public evaluation run progress; source and patch content are never exposed. */
public record LlmEvaluationRunDto(
    String runId,
    String runKey,
    String status,
    int totalSamples,
    int completedSamples,
    long totalTokens,
    BigDecimal totalCost,
    Long reportId,
    String failureCode,
    LocalDateTime submittedAt,
    LocalDateTime startedAt,
    LocalDateTime finishedAt
) {
}
