package com.repoguard.agent.notification;

import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.entity.NotificationDeliveryLog;
import com.repoguard.agent.entity.NotificationEvent;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class NotificationDeliveryLogFactory {

    private static final int MAX_FAILURE_REASON_LENGTH = 1024;

    private final Clock clock;
    private final NotificationTextLimiter textLimiter;
    private final NotificationRetrySchedule retrySchedule;

    @Autowired
    NotificationDeliveryLogFactory(NotificationTextLimiter textLimiter, NotificationRetrySchedule retrySchedule) {
        this(Clock.systemDefaultZone(), textLimiter, retrySchedule);
    }

    NotificationDeliveryLogFactory(
        Clock clock,
        NotificationTextLimiter textLimiter,
        NotificationRetrySchedule retrySchedule
    ) {
        this.clock = clock;
        this.textLimiter = Objects.requireNonNull(textLimiter, "textLimiter");
        this.retrySchedule = Objects.requireNonNull(retrySchedule, "retrySchedule");
    }

    NotificationDeliveryLog create(
        NotificationEvent event,
        NotificationChannelBinding binding,
        NotificationSendResult result
    ) {
        LocalDateTime now = LocalDateTime.now(clock);
        NotificationDeliveryLog log = new NotificationDeliveryLog();
        log.setEventId(event.getId());
        log.setBindingId(binding.getId());
        log.setTaskId(event.getTaskId());
        log.setProvider(binding.getProvider());
        log.setStatus(result.success() ? NotificationDeliveryStatus.SUCCESS.code() : NotificationDeliveryStatus.FAILED.code());
        log.setAttemptCount(retrySchedule.nextRetryCount(event.getRetryCount()));
        log.setFailureReason(result.success() ? null : textLimiter.limit(result.message(), MAX_FAILURE_REASON_LENGTH));
        log.setRequestId(result.requestId());
        log.setSentAt(now);
        log.setCreatedAt(now);
        return log;
    }
}
