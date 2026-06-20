package com.repoguard.agent.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.NotificationEventMapper;
import java.time.LocalDateTime;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

@Component
class NotificationOutboxEventStore {

    private final NotificationEventMapper eventMapper;

    NotificationOutboxEventStore(NotificationEventMapper eventMapper) {
        this.eventMapper = eventMapper;
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
}
