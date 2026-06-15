package com.repoguard.agent.dto;

public record NotificationDeliveryDto(
    Long id,
    Long eventId,
    Long bindingId,
    Long taskId,
    String provider,
    String status,
    Integer attemptCount,
    String failureReason,
    String requestId,
    String sentAt,
    String createdAt
) {
}
