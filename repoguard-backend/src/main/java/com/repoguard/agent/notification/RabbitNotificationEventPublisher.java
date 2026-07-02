package com.repoguard.agent.notification;

import com.repoguard.agent.config.RabbitNotificationQueueProperties;
import com.repoguard.agent.messaging.RabbitPublishSpec;
import com.repoguard.agent.messaging.RabbitReliableMessagePublisher;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RabbitNotificationEventPublisher implements NotificationEventPublisher {

    private final RabbitReliableMessagePublisher reliablePublisher;
    private final RabbitNotificationQueueProperties properties;

    RabbitNotificationEventPublisher(RabbitTemplate rabbitTemplate, RabbitNotificationQueueProperties properties) {
        this(new RabbitReliableMessagePublisher(rabbitTemplate), properties);
    }

    @Autowired
    public RabbitNotificationEventPublisher(
        RabbitReliableMessagePublisher reliablePublisher,
        RabbitNotificationQueueProperties properties
    ) {
        this.reliablePublisher = reliablePublisher;
        this.properties = properties;
    }

    @Override
    public void publish(NotificationEventMessage message) {
        reliablePublisher.publish(message, new RabbitPublishSpec(
            properties.getExchange(),
            properties.getRoutingKey(),
            properties.getPublishMaxAttempts(),
            properties.getPublishInitialIntervalMs(),
            properties.getPublishMultiplier(),
            properties.getPublishConfirmTimeoutMs(),
            "notification-event-%d".formatted(message.eventId())
        ));
    }
}
