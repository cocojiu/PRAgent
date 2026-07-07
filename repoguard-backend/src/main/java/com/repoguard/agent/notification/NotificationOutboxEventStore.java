package com.repoguard.agent.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.NotificationEventMapper;
import com.repoguard.agent.messaging.RabbitPublishClaim;
import com.repoguard.agent.messaging.RabbitPublishClaimConditions;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

@Component
class NotificationOutboxEventStore {

    private final NotificationEventMapper eventMapper;

    NotificationOutboxEventStore(NotificationEventMapper eventMapper) {
        this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper");
    }

    NotificationEvent createPendingEvent(
        String eventType,
        ReviewTask task,
        Long batchId,
        NotificationEventPayload payload
    ) {
        NotificationEvent event = new NotificationEvent();
        LocalDateTime now = LocalDateTime.now();
        event.setEventKey(payload.eventKey());
        event.setEventType(eventType);
        event.setTaskId(task.getId());
        event.setBatchId(batchId);
        event.setPayload(payload.json());
        event.setStatus(NotificationEventStatus.PENDING.code());
        event.setRetryCount(0);
        event.setNextRetryAt(now);
        event.setCreatedAt(now);
        event.setUpdatedAt(now);
        try {
            eventMapper.insert(event);
            return event;
        } catch (DuplicateKeyException ignored) {
            return eventMapper.selectOne(
                new LambdaQueryWrapper<NotificationEvent>()
                    .eq(NotificationEvent::getEventKey, event.getEventKey())
            );
        }
    }

    NotificationEvent loadById(Long eventId) {
        return eventMapper.selectById(eventId);
    }

    List<NotificationEvent> loadDuePublishEvents(
        LocalDateTime now,
        LocalDateTime expiredBefore,
        int maxAttempts,
        int batchSize
    ) {
        return eventMapper.selectList(
            new LambdaQueryWrapper<NotificationEvent>()
                .in(NotificationEvent::getStatus, publishRecoveryStatuses())
                .le(NotificationEvent::getNextRetryAt, now)
                .lt(NotificationEvent::getRetryCount, maxAttempts)
                .and(RabbitPublishClaimConditions.availableLambda(
                    NotificationEvent::getPublishClaimedAt,
                    expiredBefore
                ))
                .orderByAsc(NotificationEvent::getNextRetryAt)
                .last("limit " + batchSize)
        );
    }

    boolean claimForPublish(
        NotificationEvent event,
        RabbitPublishClaim claim
    ) {
        Objects.requireNonNull(claim, "claim");
        int updated = eventMapper.update(
            new UpdateWrapper<NotificationEvent>()
                .eq("id", event.getId())
                .in("status", publishRecoveryStatuses())
                .le("next_retry_at", claim.claimedAt())
                .lt("retry_count", claim.maxAttempts())
                .and(RabbitPublishClaimConditions.availableColumn(
                    "publish_claimed_at",
                    claim.expiredBefore()
                ))
                .set("publish_claimed_at", claim.claimedAt())
                .set("publish_claimed_by", claim.instanceId())
                .set("updated_at", claim.claimedAt())
        );
        if (updated <= 0) {
            return false;
        }
        event.setPublishClaimedAt(claim.claimedAt());
        event.setPublishClaimedBy(claim.instanceId());
        event.setUpdatedAt(claim.claimedAt());
        return true;
    }

    private List<String> publishRecoveryStatuses() {
        return List.of(
            NotificationEventStatus.PENDING.code(),
            NotificationEventStatus.PUBLISH_FAILED.code(),
            NotificationEventStatus.DELIVERY_FAILED.code()
        );
    }
}
