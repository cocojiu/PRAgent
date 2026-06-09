package com.repoguard.agent.dto;

public record ReviewRetryResponse(
    Long taskId,
    String status,
    String message,
    Integer retryCount
) {
}
