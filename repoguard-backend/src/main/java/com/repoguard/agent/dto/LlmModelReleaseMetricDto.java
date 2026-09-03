package com.repoguard.agent.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Public aggregate-only runtime evidence for a model release window. */
public record LlmModelReleaseMetricDto(
    Long id,
    Long releaseId,
    String releaseKey,
    String provider,
    String modelName,
    LocalDateTime windowStart,
    LocalDateTime windowEnd,
    Long sampleCount,
    Long totalTokens,
    BigDecimal totalCost,
    Long p95LatencyMs,
    Long parseFailureCount,
    Long fallbackCount,
    Long rollbackCount,
    String alertState,
    List<String> alertCodes,
    String action,
    String alertFingerprint,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
