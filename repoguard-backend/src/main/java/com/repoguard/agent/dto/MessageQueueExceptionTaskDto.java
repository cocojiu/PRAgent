package com.repoguard.agent.dto;

public record MessageQueueExceptionTaskDto(
    Long taskId,
    String organization,
    String repository,
    Integer prNumber,
    String status,
    Integer publishAttempts,
    String nextRetryAt,
    String claimedBy,
    String claimedAt,
    String lastError
) {
}
