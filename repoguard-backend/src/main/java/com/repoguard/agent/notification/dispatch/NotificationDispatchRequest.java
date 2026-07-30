package com.repoguard.agent.notification.dispatch;

public record NotificationDispatchRequest(
    String eventType,
    Long batchId,
    int findingCount,
    int commentSucceededCount,
    int commentFailedCount,
    int commentSkippedCount
) {
}
