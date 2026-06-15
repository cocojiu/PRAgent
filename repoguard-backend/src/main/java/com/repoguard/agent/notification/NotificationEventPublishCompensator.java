package com.repoguard.agent.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.config.RabbitNotificationQueueProperties;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.mapper.NotificationEventMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NotificationEventPublishCompensator {

    private final NotificationEventMapper eventMapper;
    private final NotificationDispatchService dispatchService;
    private final RabbitNotificationQueueProperties properties;

    public NotificationEventPublishCompensator(
        NotificationEventMapper eventMapper,
        NotificationDispatchService dispatchService,
        RabbitNotificationQueueProperties properties
    ) {
        this.eventMapper = eventMapper;
        this.dispatchService = dispatchService;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.rabbit.notification.publish-compensation-interval-ms:60000}")
    public void compensate() {
        List<NotificationEvent> events = eventMapper.selectList(
            new LambdaQueryWrapper<NotificationEvent>()
                .in(NotificationEvent::getStatus, List.of("PENDING", "PUBLISH_FAILED", "DELIVERY_FAILED"))
                .le(NotificationEvent::getNextRetryAt, LocalDateTime.now())
                .lt(NotificationEvent::getRetryCount, Math.max(1, properties.getPublishCompensationMaxAttempts()))
                .orderByAsc(NotificationEvent::getNextRetryAt)
                .last("limit " + Math.max(1, properties.getPublishCompensationBatchSize()))
        );
        for (NotificationEvent event : events) {
            dispatchService.publishExistingEvent(event.getId());
        }
    }
}
