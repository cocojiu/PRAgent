package com.repoguard.agent.review.quality;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Tenant-scoped, aggregate-only runtime evidence for one release and time window. */
public record LlmModelReleaseMetricSnapshot(
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

    public LlmModelReleaseMetricSnapshot {
        alertCodes = alertCodes == null ? List.of() : List.copyOf(alertCodes);
        totalCost = totalCost == null ? BigDecimal.ZERO : totalCost.max(BigDecimal.ZERO);
        sampleCount = nonNegative(sampleCount);
        totalTokens = nonNegative(totalTokens);
        p95LatencyMs = nonNegative(p95LatencyMs);
        parseFailureCount = nonNegative(parseFailureCount);
        fallbackCount = nonNegative(fallbackCount);
        rollbackCount = nonNegative(rollbackCount);
    }

    private static long nonNegative(Long value) {
        return value == null ? 0L : Math.max(0L, value);
    }
}
