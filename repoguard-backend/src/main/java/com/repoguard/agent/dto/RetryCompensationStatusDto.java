package com.repoguard.agent.dto;

public record RetryCompensationStatusDto(
    Integer maxAttempts,
    Long intervalMs,
    Integer batchSize,
    Long leaseMs,
    Long claimedTaskCount,
    String latestSuccessAt,
    String latestFailureReason
) {
}
