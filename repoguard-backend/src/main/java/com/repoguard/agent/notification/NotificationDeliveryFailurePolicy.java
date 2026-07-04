package com.repoguard.agent.notification;

import com.repoguard.agent.entity.NotificationEvent;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class NotificationDeliveryFailurePolicy {

    private static final int MAX_ATTEMPTS = 5;
    private static final String FAILURE_MESSAGE = "One or more notification bindings failed";

    private final NotificationRetrySchedule retrySchedule;

    @Autowired
    NotificationDeliveryFailurePolicy(NotificationRetrySchedule retrySchedule) {
        this.retrySchedule = Objects.requireNonNull(retrySchedule, "retrySchedule");
    }

    NotificationDeliveryFailureDecision decide(NotificationEvent event) {
        int nextRetryCount = retrySchedule.nextRetryCount(event.getRetryCount());
        boolean dead = nextRetryCount >= MAX_ATTEMPTS;
        return new NotificationDeliveryFailureDecision(
            dead ? NotificationEventStatus.DEAD.code() : NotificationEventStatus.DELIVERY_FAILED.code(),
            nextRetryCount,
            dead ? null : retrySchedule.nextRetryAt(nextRetryCount),
            FAILURE_MESSAGE
        );
    }
}
