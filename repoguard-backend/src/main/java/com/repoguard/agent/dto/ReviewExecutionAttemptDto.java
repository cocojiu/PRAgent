package com.repoguard.agent.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ReviewExecutionAttemptDto(
    Long id,
    Long taskId,
    Integer attemptNo,
    Long generation,
    String commitSha,
    String inputFingerprint,
    String workerId,
    String status,
    String failureCategory,
    String budgetExhaustedStage,
    Long policyVersion,
    String promptVersion,
    String contextVersion,
    String schemaVersion,
    String verifierVersion,
    String aggregationVersion,
    Long diffFetchMs,
    Long reviewMs,
    Long persistMs,
    Long totalMs,
    Integer promptTokens,
    Integer completionTokens,
    Integer totalTokens,
    BigDecimal estimatedCost,
    LocalDateTime queuedAt,
    LocalDateTime startedAt,
    LocalDateTime finishedAt,
    LocalDateTime payloadPurgedAt,
    boolean current
) {
}
