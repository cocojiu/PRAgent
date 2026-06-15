package com.repoguard.agent.dto;

public record NotificationEventDto(
    Long id,
    String eventKey,
    String eventType,
    Long taskId,
    Long batchId,
    String status,
    Integer retryCount,
    String nextRetryAt,
    String lastError,
    NotificationDeliverySummaryDto deliverySummary,
    String createdAt,
    String updatedAt
) {
}
