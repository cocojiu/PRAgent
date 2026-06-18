package com.repoguard.agent.notification;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.mapper.NotificationEventMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
class NotificationDeliveryEventStateUpdater {

    private final NotificationEventMapper eventMapper;
    private final Clock clock;

    NotificationDeliveryEventStateUpdater(NotificationEventMapper eventMapper) {
        this(eventMapper, Clock.systemDefaultZone());
    }

    NotificationDeliveryEventStateUpdater(NotificationEventMapper eventMapper, Clock clock) {
        this.eventMapper = eventMapper;
        this.clock = clock;
    }

    void markDelivering(NotificationEvent event) {
        eventMapper.update(
            new UpdateWrapper<NotificationEvent>()
                .eq("id", event.getId())
                .ne("status", NotificationEventStatus.DELIVERED.code())
                .set("status", NotificationEventStatus.DELIVERING.code())
                .set("updated_at", now())
        );
    }

    void markFailed(NotificationEvent event, NotificationDeliveryFailureDecision decision) {
        eventMapper.update(
            new UpdateWrapper<NotificationEvent>()
                .eq("id", event.getId())
                .set("status", decision.status())
                .set("retry_count", decision.retryCount())
                .set("next_retry_at", decision.nextRetryAt())
                .set("last_error", decision.lastError())
                .set("updated_at", now())
        );
    }

    void markDelivered(NotificationEvent event) {
        eventMapper.update(
            new UpdateWrapper<NotificationEvent>()
                .eq("id", event.getId())
                .set("status", NotificationEventStatus.DELIVERED.code())
                .set("next_retry_at", null)
                .set("last_error", null)
                .set("updated_at", now())
        );
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
