package com.repoguard.agent.dto;

public record MessageQueueRequeueResponse(
    Long taskId,
    String status,
    String message,
    Integer publishAttempts
) {
}
