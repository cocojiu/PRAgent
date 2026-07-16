package com.repoguard.agent.notification;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.mapper.NotificationEventMapper;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
class NotificationPublishEventStateUpdater {

    private final NotificationEventMapper eventMapper;

    NotificationPublishEventStateUpdater(NotificationEventMapper eventMapper) {
        this.eventMapper = eventMapper;
    }

    void markPublished(NotificationEvent event) {
        eventMapper.update(
            new UpdateWrapper<NotificationEvent>()
                .eq("id", event.getId())
                .ne("status", NotificationEventStatus.DELIVERED.code())
                .set("status", NotificationEventStatus.PUBLISHED.code())
                .set("last_error", null)
                .set("publish_claimed_at", null)
                .set("publish_claimed_by", null)
                .set("delivery_claimed_at", null)
                .set("delivery_claimed_by", null)
                .set("updated_at", LocalDateTime.now())
        );
    }

    void markPublishFailed(NotificationEvent event, NotificationPublishFailureDecision decision) {
        eventMapper.update(
            new UpdateWrapper<NotificationEvent>()
                .eq("id", event.getId())
                .ne("status", NotificationEventStatus.DELIVERED.code())
                .set("status", decision.status())
                .set("retry_count", decision.retryCount())
                .set("next_retry_at", decision.nextRetryAt())
                .set("last_error", decision.lastError())
                .set("publish_claimed_at", null)
                .set("publish_claimed_by", null)
                .set("delivery_claimed_at", null)
                .set("delivery_claimed_by", null)
                .set("updated_at", LocalDateTime.now())
        );
    }
}
