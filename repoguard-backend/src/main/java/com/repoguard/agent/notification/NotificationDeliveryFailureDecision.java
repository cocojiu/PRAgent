package com.repoguard.agent.notification;

import java.time.LocalDateTime;

record NotificationDeliveryFailureDecision(
    String status,
    int retryCount,
    LocalDateTime nextRetryAt,
    String lastError
) {
}
