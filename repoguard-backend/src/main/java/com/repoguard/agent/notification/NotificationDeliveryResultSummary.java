package com.repoguard.agent.notification;

import java.util.Objects;

record NotificationDeliveryResultSummary(int attemptedCount, int successCount, int failureCount) {

    static NotificationDeliveryResultSummary empty() {
        return new NotificationDeliveryResultSummary(0, 0, 0);
    }

    NotificationDeliveryResultSummary add(NotificationSendResult result) {
        Objects.requireNonNull(result, "result must not be null");
        return result.success()
            ? new NotificationDeliveryResultSummary(attemptedCount + 1, successCount + 1, failureCount)
            : new NotificationDeliveryResultSummary(attemptedCount + 1, successCount, failureCount + 1);
    }

    boolean anyFailed() {
        return failureCount > 0;
    }
}
