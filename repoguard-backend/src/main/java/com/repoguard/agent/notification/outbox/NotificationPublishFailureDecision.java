package com.repoguard.agent.notification.outbox;

import java.time.LocalDateTime;

public record NotificationPublishFailureDecision(
    String status,
    int retryCount,
    LocalDateTime nextRetryAt,
    String lastError
) {
}
