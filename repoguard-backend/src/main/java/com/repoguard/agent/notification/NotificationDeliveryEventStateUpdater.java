package com.repoguard.agent.notification;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.mapper.NotificationEventMapper;
import com.repoguard.agent.notification.delivery.NotificationDeliveryClaim;
import com.repoguard.agent.notification.delivery.NotificationDeliveryFailureDecision;
import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class NotificationDeliveryEventStateUpdater {

    private final NotificationEventMapper eventMapper;
    private final Clock clock;

    @Autowired
    NotificationDeliveryEventStateUpdater(NotificationEventMapper eventMapper) {
        this(eventMapper, Clock.systemDefaultZone());
    }

    NotificationDeliveryEventStateUpdater(NotificationEventMapper eventMapper, Clock clock) {
        this.eventMapper = eventMapper;
        this.clock = clock;
    }

    boolean claimForDelivery(NotificationEvent event, NotificationDeliveryClaim claim) {
        int updated = eventMapper.update(
            new UpdateWrapper<NotificationEvent>()
                .eq("id", event.getId())
                .in(
                    "status",
                    NotificationEventStatus.PUBLISHING.code(),
                    NotificationEventStatus.PUBLISHED.code()
                )
                .isNull("delivery_claimed_at")
                .set("status", NotificationEventStatus.DELIVERING.code())
                .set("publish_claimed_at", null)
                .set("publish_claimed_by", null)
                .set("delivery_claimed_at", claim.claimedAt())
                .set("delivery_claimed_by", claim.claimedBy())
                .set("updated_at", claim.claimedAt())
        );
        return updated == 1;
    }

    boolean markFailed(NotificationEvent event, NotificationDeliveryFailureDecision decision) {
        int updated = eventMapper.update(
            ownedDelivery(event)
                .set("status", decision.status())
                .set("retry_count", decision.retryCount())
                .set("next_retry_at", decision.nextRetryAt())
                .set("last_error", decision.lastError())
                .set("delivery_claimed_at", null)
                .set("delivery_claimed_by", null)
                .set("updated_at", now())
        );
        return updated == 1;
    }

    boolean markDelivered(NotificationEvent event) {
        int updated = eventMapper.update(
            ownedDelivery(event)
                .set("status", NotificationEventStatus.DELIVERED.code())
                .set("next_retry_at", null)
                .set("last_error", null)
                .set("delivery_claimed_at", null)
                .set("delivery_claimed_by", null)
                .set("updated_at", now())
        );
        return updated == 1;
    }

    boolean recoverExpired(NotificationEvent event, NotificationDeliveryFailureDecision decision) {
        int updated = eventMapper.update(
            ownedDelivery(event)
                .set("status", decision.status())
                .set("retry_count", decision.retryCount())
                .set("next_retry_at", decision.nextRetryAt())
                .set("last_error", "Notification delivery claim expired")
                .set("delivery_claimed_at", null)
                .set("delivery_claimed_by", null)
                .set("updated_at", now())
        );
        return updated == 1;
    }

    private UpdateWrapper<NotificationEvent> ownedDelivery(NotificationEvent event) {
        return new UpdateWrapper<NotificationEvent>()
            .eq("id", event.getId())
            .eq("status", NotificationEventStatus.DELIVERING.code())
            .eq("delivery_claimed_at", event.getDeliveryClaimedAt())
            .eq("delivery_claimed_by", event.getDeliveryClaimedBy());
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
