package com.repoguard.agent.dto;

public record ManualReviewResponse(
    Long taskId,
    String status,
    String message,
    Boolean existing,
    String source,
    String triggerSource
) {
}
