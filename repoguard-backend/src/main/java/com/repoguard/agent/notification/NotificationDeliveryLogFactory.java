package com.repoguard.agent.notification;

import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.entity.NotificationDeliveryLog;
import com.repoguard.agent.entity.NotificationEvent;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
class NotificationDeliveryLogFactory {

    private static final int MAX_FAILURE_REASON_LENGTH = 1024;

    private final Clock clock;

    NotificationDeliveryLogFactory() {
        this(Clock.systemDefaultZone());
    }

    NotificationDeliveryLogFactory(Clock clock) {
        this.clock = clock;
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
        log.setAttemptCount(safe(event.getRetryCount()) + 1);
        log.setFailureReason(result.success() ? null : truncate(result.message(), MAX_FAILURE_REASON_LENGTH));
        log.setRequestId(result.requestId());
        log.setSentAt(now);
        log.setCreatedAt(now);
        return log;
    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
