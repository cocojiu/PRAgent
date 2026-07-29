package com.repoguard.agent.notification.delivery;

import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.notification.NotificationEventStatus;
import com.repoguard.agent.notification.retry.NotificationRetrySchedule;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class NotificationDeliveryFailurePolicy {

    private static final int MAX_ATTEMPTS = 5;
    private static final String FAILURE_MESSAGE = "One or more notification bindings failed";

    private final NotificationRetrySchedule retrySchedule;

    @Autowired
    public NotificationDeliveryFailurePolicy(NotificationRetrySchedule retrySchedule) {
        this.retrySchedule = Objects.requireNonNull(retrySchedule, "retrySchedule");
    }

    public NotificationDeliveryFailureDecision decide(NotificationEvent event) {
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
