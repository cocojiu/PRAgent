package com.repoguard.agent.notification;

import com.repoguard.agent.common.SensitiveTextSanitizer;
import com.repoguard.agent.entity.NotificationEvent;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class NotificationPublishFailurePolicy {

    private static final int MAX_ERROR_LENGTH = 1024;

    private final NotificationRetrySchedule retrySchedule;

    @Autowired
    NotificationPublishFailurePolicy(NotificationRetrySchedule retrySchedule) {
        this.retrySchedule = Objects.requireNonNull(retrySchedule, "retrySchedule");
    }

    NotificationPublishFailureDecision decide(
        NotificationEvent event,
        RuntimeException ex,
        int maxAttempts
    ) {
        int nextRetryCount = retrySchedule.nextRetryCount(event.getRetryCount());
        boolean dead = nextRetryCount >= Math.max(1, maxAttempts);
        return new NotificationPublishFailureDecision(
            dead ? NotificationEventStatus.DEAD.code() : NotificationEventStatus.PUBLISH_FAILED.code(),
            nextRetryCount,
            dead ? null : retrySchedule.nextRetryAt(nextRetryCount),
            truncate(errorMessage(ex), MAX_ERROR_LENGTH)
        );
    }

    private String errorMessage(RuntimeException ex) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return SensitiveTextSanitizer.sanitize(message);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
