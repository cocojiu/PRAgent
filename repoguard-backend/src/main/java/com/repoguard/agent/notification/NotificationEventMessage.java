package com.repoguard.agent.notification;

public record NotificationEventMessage(
    Long eventId,
    String eventKey,
    String eventType,
    Long taskId,
    Long batchId
) {
}
