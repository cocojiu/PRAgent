package com.repoguard.agent.notification.delivery;

import java.util.Objects;

public record NotificationDeliveryResultSummary(int attemptedCount, int successCount, int failureCount) {

    public static NotificationDeliveryResultSummary empty() {
        return new NotificationDeliveryResultSummary(0, 0, 0);
    }

    public NotificationDeliveryResultSummary add(NotificationSendResult result) {
        Objects.requireNonNull(result, "result must not be null");
        return result.success()
            ? new NotificationDeliveryResultSummary(attemptedCount + 1, successCount + 1, failureCount)
            : new NotificationDeliveryResultSummary(attemptedCount + 1, successCount, failureCount + 1);
    }

    public boolean anyFailed() {
        return failureCount > 0;
    }
}
