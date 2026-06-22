package com.repoguard.agent.notification;

record NotificationDispatchRequest(
    String eventType,
    Long batchId,
    int findingCount,
    int commentSucceededCount,
    int commentFailedCount,
    int commentSkippedCount
) {
}
