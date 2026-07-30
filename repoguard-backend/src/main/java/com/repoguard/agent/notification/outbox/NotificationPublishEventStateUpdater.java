package com.repoguard.agent.notification.outbox;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.mapper.NotificationEventMapper;
import com.repoguard.agent.notification.NotificationEventStatus;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
public class NotificationPublishEventStateUpdater {

    private final NotificationEventMapper eventMapper;

    public NotificationPublishEventStateUpdater(NotificationEventMapper eventMapper) {
        this.eventMapper = eventMapper;
    }

    public boolean markPublished(NotificationEvent event) {
        int updated = eventMapper.update(
            ownedPublish(event)
                .set("status", NotificationEventStatus.PUBLISHED.code())
                .set("next_retry_at", null)
                .set("last_error", null)
                .set("publish_claimed_at", null)
                .set("publish_claimed_by", null)
                .set("updated_at", LocalDateTime.now())
        );
        if (updated != 1) {
            return false;
        }
        event.setStatus(NotificationEventStatus.PUBLISHED.code());
        event.setNextRetryAt(null);
        event.setLastError(null);
        event.setPublishClaimedAt(null);
        event.setPublishClaimedBy(null);
        return true;
    }

    public boolean markPublishFailed(NotificationEvent event, NotificationPublishFailureDecision decision) {
        int updated = eventMapper.update(
            ownedPublish(event)
                .set("status", decision.status())
                .set("retry_count", decision.retryCount())
                .set("next_retry_at", decision.nextRetryAt())
                .set("last_error", decision.lastError())
                .set("publish_claimed_at", null)
                .set("publish_claimed_by", null)
                .set("updated_at", LocalDateTime.now())
        );
        if (updated != 1) {
            return false;
        }
        event.setStatus(decision.status());
        event.setRetryCount(decision.retryCount());
        event.setNextRetryAt(decision.nextRetryAt());
        event.setLastError(decision.lastError());
        event.setPublishClaimedAt(null);
        event.setPublishClaimedBy(null);
        return true;
    }

    private UpdateWrapper<NotificationEvent> ownedPublish(NotificationEvent event) {
        return new UpdateWrapper<NotificationEvent>()
            .eq("id", event.getId())
            .eq("status", NotificationEventStatus.PUBLISHING.code())
            .eq("publish_claimed_at", event.getPublishClaimedAt())
            .eq("publish_claimed_by", event.getPublishClaimedBy());
    }
}
