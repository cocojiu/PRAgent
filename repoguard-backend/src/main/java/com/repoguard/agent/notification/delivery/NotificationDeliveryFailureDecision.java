package com.repoguard.agent.notification.delivery;

import java.time.LocalDateTime;

public record NotificationDeliveryFailureDecision(
    String status,
    int retryCount,
    LocalDateTime nextRetryAt,
    String lastError
) {
}
