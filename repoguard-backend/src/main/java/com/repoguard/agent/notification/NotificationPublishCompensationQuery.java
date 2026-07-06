package com.repoguard.agent.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.config.RabbitNotificationQueueProperties;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.mapper.NotificationEventMapper;
import com.repoguard.agent.messaging.RabbitPublishCompensationPolicy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class NotificationPublishCompensationQuery {

    private final NotificationEventMapper eventMapper;
    private final RabbitNotificationQueueProperties properties;
    private final RabbitPublishCompensationPolicy compensationPolicy;

    @Autowired
    NotificationPublishCompensationQuery(
        NotificationEventMapper eventMapper,
        RabbitNotificationQueueProperties properties,
        RabbitPublishCompensationPolicy compensationPolicy
    ) {
        this.eventMapper = Objects.requireNonNull(eventMapper, "eventMapper");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.compensationPolicy = Objects.requireNonNull(compensationPolicy, "compensationPolicy");
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
        return compensationPolicy.maxAttempts(properties);
    }

    int batchSize() {
        return compensationPolicy.batchSize(properties);
    }
}
