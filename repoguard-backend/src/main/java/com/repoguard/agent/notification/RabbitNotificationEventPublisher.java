package com.repoguard.agent.notification;

import com.repoguard.agent.config.RabbitNotificationQueueProperties;
import com.repoguard.agent.messaging.MessagePublishException;
import com.repoguard.agent.messaging.RabbitPublishResult;
import com.repoguard.agent.messaging.RabbitPublishSpec;
import com.repoguard.agent.messaging.RabbitPublishFailurePhase;
import com.repoguard.agent.messaging.RabbitPublishFailureMetricsRecorder;
import com.repoguard.agent.messaging.RabbitPublishSpecFactory;
import com.repoguard.agent.messaging.RabbitReliableMessagePublisher;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RabbitNotificationEventPublisher implements NotificationEventPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(RabbitNotificationEventPublisher.class);

    private final RabbitReliableMessagePublisher reliablePublisher;
    private final RabbitNotificationQueueProperties properties;
    private final RabbitPublishSpecFactory specFactory;
    private final RabbitPublishFailureMetricsRecorder metricsRecorder;

    @Autowired
    public RabbitNotificationEventPublisher(
        RabbitReliableMessagePublisher reliablePublisher,
        RabbitNotificationQueueProperties properties,
        RabbitPublishSpecFactory specFactory,
        RabbitPublishFailureMetricsRecorder metricsRecorder
    ) {
        this.reliablePublisher = Objects.requireNonNull(reliablePublisher, "reliablePublisher");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.specFactory = Objects.requireNonNull(specFactory, "specFactory");
        this.metricsRecorder = Objects.requireNonNull(metricsRecorder, "metricsRecorder");
    }

    @Override
    public void publish(NotificationEventMessage message) {
        publish(message, spec(message));
    }

    @Override
    public void publishOnce(NotificationEventMessage message) {
        publish(message, spec(message).singleAttempt());
    }

    private void publish(NotificationEventMessage message, RabbitPublishSpec publishSpec) {
        try {
            RabbitPublishResult result = reliablePublisher.publish(message, publishSpec);
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
                publishSpec.normalizedMaxAttempts(),
                failureReason
            );
            metricsRecorder.recordFailed(RabbitPublishFailurePhase.NOTIFICATION, failureReason);
            throw ex;
        }
    }

    private RabbitPublishSpec spec(NotificationEventMessage message) {
        return specFactory.notificationEvent(properties, message.eventId());
    }

    private String safePart(String value) {
        return value == null || value.isBlank() ? "<unknown>" : value.trim();
    }
}
