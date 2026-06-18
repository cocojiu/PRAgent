package com.repoguard.agent.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.config.RabbitNotificationQueueProperties;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.mapper.NotificationEventMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class NotificationPublishCompensationQuery {

    private final NotificationEventMapper eventMapper;
    private final RabbitNotificationQueueProperties properties;

    NotificationPublishCompensationQuery(
        NotificationEventMapper eventMapper,
        RabbitNotificationQueueProperties properties
    ) {
        this.eventMapper = eventMapper;
        this.properties = properties;
    }

    List<NotificationEvent> loadDueEvents() {
        return eventMapper.selectList(
            new LambdaQueryWrapper<NotificationEvent>()
                .in(NotificationEvent::getStatus, List.of(
                    NotificationEventStatus.PENDING.code(),
                    NotificationEventStatus.PUBLISH_FAILED.code(),
                    NotificationEventStatus.DELIVERY_FAILED.code()
                ))
                .le(NotificationEvent::getNextRetryAt, LocalDateTime.now())
                .lt(NotificationEvent::getRetryCount, maxAttempts())
                .orderByAsc(NotificationEvent::getNextRetryAt)
                .last("limit " + batchSize())
        );
    }

    int maxAttempts() {
        return Math.max(1, properties.getPublishCompensationMaxAttempts());
    }

    int batchSize() {
        return Math.max(1, properties.getPublishCompensationBatchSize());
    }
}
