package com.repoguard.agent.notification;

import java.time.LocalDateTime;

record NotificationPublishFailureDecision(
    String status,
    int retryCount,
    LocalDateTime nextRetryAt,
    String lastError
) {
}
