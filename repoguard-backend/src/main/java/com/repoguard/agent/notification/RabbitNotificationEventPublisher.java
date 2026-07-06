package com.repoguard.agent.notification;

import com.repoguard.agent.config.RabbitNotificationQueueProperties;
import com.repoguard.agent.messaging.MessagePublishException;
import com.repoguard.agent.messaging.RabbitPublishResult;
import com.repoguard.agent.messaging.RabbitPublishSpec;
import com.repoguard.agent.messaging.RabbitReliableMessagePublisher;
import com.repoguard.agent.observability.RepoGuardMetrics;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RabbitNotificationEventPublisher implements NotificationEventPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(RabbitNotificationEventPublisher.class);

    private final RabbitReliableMessagePublisher reliablePublisher;
    private final RabbitNotificationQueueProperties properties;
    private final RepoGuardMetrics metrics;

    RabbitNotificationEventPublisher(RabbitTemplate rabbitTemplate, RabbitNotificationQueueProperties properties) {
        this(new RabbitReliableMessagePublisher(rabbitTemplate), properties, null);
    }

    @Autowired
    public RabbitNotificationEventPublisher(
        RabbitReliableMessagePublisher reliablePublisher,
        RabbitNotificationQueueProperties properties,
        RepoGuardMetrics metrics
    ) {
        this.reliablePublisher = reliablePublisher;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    public void publish(NotificationEventMessage message) {
        try {
            RabbitPublishResult result = reliablePublisher.publish(message, spec(message));
            LOGGER.info(
                "Rabbit notification event published eventId={} taskId={} eventType={} operation=rabbit_notification_publish result=success attempt={} exchange={} routingKey={}",
                message.eventId(),
                message.taskId(),
                safePart(message.eventType()),
                result.attempt(),
                properties.getExchange(),
                properties.getRoutingKey()
            );
        } catch (MessagePublishException ex) {
            String failureReason = reliablePublisher.failureReason(ex);
            LOGGER.warn(
                "Rabbit notification event publish failed eventId={} taskId={} eventType={} operation=rabbit_notification_publish result=failed maxAttempts={} failureReason={}",
                message.eventId(),
                message.taskId(),
                safePart(message.eventType()),
                Math.max(1, properties.getPublishMaxAttempts()),
                failureReason
            );
            if (metrics != null) {
                metrics.rabbitPublishFailed("notification", failureReason);
            }
            throw ex;
        }
    }

    private RabbitPublishSpec spec(NotificationEventMessage message) {
        return RabbitPublishSpec.from(properties, "notification-event-%d".formatted(message.eventId()));
    }

    private String safePart(String value) {
        return value == null || value.isBlank() ? "<unknown>" : value.trim();
    }
}
