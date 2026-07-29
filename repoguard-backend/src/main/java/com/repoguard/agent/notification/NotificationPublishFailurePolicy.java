package com.repoguard.agent.notification;

import com.repoguard.agent.common.SensitiveTextSanitizer;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.messaging.RabbitPublishCompensationPolicy;
import com.repoguard.agent.notification.retry.NotificationRetrySchedule;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class NotificationPublishFailurePolicy {

    private static final int MAX_ERROR_LENGTH = 1024;

    private final NotificationRetrySchedule retrySchedule;
    private final NotificationTextLimiter textLimiter;
    private final RabbitPublishCompensationPolicy compensationPolicy;

    @Autowired
    NotificationPublishFailurePolicy(
        NotificationRetrySchedule retrySchedule,
        NotificationTextLimiter textLimiter,
        RabbitPublishCompensationPolicy compensationPolicy
    ) {
        this.retrySchedule = Objects.requireNonNull(retrySchedule, "retrySchedule");
        this.textLimiter = Objects.requireNonNull(textLimiter, "textLimiter");
        this.compensationPolicy = Objects.requireNonNull(compensationPolicy, "compensationPolicy");
    }

    NotificationPublishFailureDecision decide(
        NotificationEvent event,
        RuntimeException ex,
        int maxAttempts
    ) {
        int nextRetryCount = compensationPolicy.nextAttempt(event.getRetryCount());
        boolean dead = compensationPolicy.isTerminalAttempt(nextRetryCount, maxAttempts);
        return new NotificationPublishFailureDecision(
            dead ? NotificationEventStatus.DEAD.code() : NotificationEventStatus.PUBLISH_FAILED.code(),
            nextRetryCount,
            dead ? null : retrySchedule.nextRetryAt(nextRetryCount),
            textLimiter.limit(errorMessage(ex), MAX_ERROR_LENGTH)
        );
    }

    private String errorMessage(RuntimeException ex) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return SensitiveTextSanitizer.sanitize(message);
    }
}
