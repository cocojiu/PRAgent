package com.repoguard.agent.notification;

import com.repoguard.agent.entity.NotificationEvent;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
class NotificationDeliveryFailurePolicy {

    private static final int[] RETRY_MINUTES = {1, 5, 15, 30, 60};
    private static final int MAX_ATTEMPTS = 5;
    private static final String FAILURE_MESSAGE = "One or more notification bindings failed";

    private final Clock clock;

    NotificationDeliveryFailurePolicy() {
        this(Clock.systemDefaultZone());
    }

    NotificationDeliveryFailurePolicy(Clock clock) {
        this.clock = clock;
    }

    NotificationDeliveryFailureDecision decide(NotificationEvent event) {
        int nextRetryCount = safe(event.getRetryCount()) + 1;
        boolean dead = nextRetryCount >= MAX_ATTEMPTS;
        return new NotificationDeliveryFailureDecision(
            dead ? NotificationEventStatus.DEAD.code() : NotificationEventStatus.DELIVERY_FAILED.code(),
            nextRetryCount,
            dead ? null : nextRetryAt(nextRetryCount),
            FAILURE_MESSAGE
        );
    }

    private LocalDateTime nextRetryAt(int retryCount) {
        int index = Math.min(Math.max(0, retryCount - 1), RETRY_MINUTES.length - 1);
        return LocalDateTime.now(clock).plusMinutes(RETRY_MINUTES[index]);
    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }
}
