package com.repoguard.agent.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record LlmModelReleaseDto(
    Long id,
    String releaseKey,
    String provider,
    String modelName,
    String promptVersion,
    String contextVersion,
    String schemaVersion,
    String datasetId,
    String datasetVersion,
    String datasetFingerprint,
    String state,
    Integer trafficPercent,
    Boolean qualityGatePassed,
    BigDecimal precisionRate,
    BigDecimal recallRate,
    BigDecimal anchorRate,
    BigDecimal duplicateRate,
    BigDecimal parseFailureRate,
    Long p95LatencyMs,
    BigDecimal averageCost,
    Long totalTokens,
    List<String> blockers,
    String rollbackReason,
    String createdBy,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
