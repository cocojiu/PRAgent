package com.repoguard.agent.notification;

import com.repoguard.agent.entity.NotificationEvent;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class NotificationPublishFailurePolicy {

    private static final int[] RETRY_MINUTES = {1, 5, 15, 30, 60};
    private static final int MAX_ERROR_LENGTH = 1024;

    private final Clock clock;

    @Autowired
    NotificationPublishFailurePolicy() {
        this(Clock.systemDefaultZone());
    }

    NotificationPublishFailurePolicy(Clock clock) {
        this.clock = clock;
    }

    NotificationPublishFailureDecision decide(
        NotificationEvent event,
        RuntimeException ex,
        int maxAttempts
    ) {
        int nextRetryCount = safe(event.getRetryCount()) + 1;
        boolean dead = nextRetryCount >= Math.max(1, maxAttempts);
        return new NotificationPublishFailureDecision(
            dead ? NotificationEventStatus.DEAD.code() : NotificationEventStatus.PUBLISH_FAILED.code(),
            nextRetryCount,
            dead ? null : nextRetryAt(nextRetryCount),
            truncate(errorMessage(ex), MAX_ERROR_LENGTH)
        );
    }

    private LocalDateTime nextRetryAt(int retryCount) {
        int index = Math.min(Math.max(0, retryCount - 1), RETRY_MINUTES.length - 1);
        return LocalDateTime.now(clock).plusMinutes(RETRY_MINUTES[index]);
    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }

    private String errorMessage(RuntimeException ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
