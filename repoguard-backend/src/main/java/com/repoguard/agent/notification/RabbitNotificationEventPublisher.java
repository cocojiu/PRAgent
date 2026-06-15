package com.repoguard.agent.notification;

import com.repoguard.agent.config.RabbitNotificationQueueProperties;
import com.repoguard.agent.messaging.MessagePublishException;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class RabbitNotificationEventPublisher implements NotificationEventPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final RabbitNotificationQueueProperties properties;

    public RabbitNotificationEventPublisher(RabbitTemplate rabbitTemplate, RabbitNotificationQueueProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    @Override
    public void publish(NotificationEventMessage message) {
        int attempts = Math.max(1, properties.getPublishMaxAttempts());
        long backoffMs = Math.max(0, properties.getPublishInitialIntervalMs());
        MessagePublishException lastFailure = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                publishOnce(message, attempt);
                return;
            } catch (MessagePublishException ex) {
                lastFailure = ex;
                if (attempt == attempts) {
                    break;
                }
                sleep(backoffMs);
                backoffMs = Math.max(0, Math.round(backoffMs * (properties.getPublishMultiplier() <= 0 ? 1.0 : properties.getPublishMultiplier())));
            }
        }
        throw lastFailure == null ? new MessagePublishException("Notification event publish failed") : lastFailure;
    }

    private void publishOnce(NotificationEventMessage message, int attempt) {
        CorrelationData correlationData = new CorrelationData("notification-event-%d-attempt-%d-%s".formatted(message.eventId(), attempt, UUID.randomUUID()));
        try {
            rabbitTemplate.convertAndSend(properties.getExchange(), properties.getRoutingKey(), message, correlationData);
            CorrelationData.Confirm confirm = correlationData.getFuture()
                .get(Math.max(1, properties.getPublishConfirmTimeoutMs()), TimeUnit.MILLISECONDS);
            ReturnedMessage returned = correlationData.getReturned();
            if (returned != null) {
                throw new MessagePublishException("Notification message was returned as unroutable: " + returned.getReplyText());
            }
            if (!confirm.ack()) {
                throw new MessagePublishException("Notification publisher confirm was nacked: " + confirm.reason());
            }
        } catch (AmqpException ex) {
            throw new MessagePublishException("Notification publish attempt failed", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MessagePublishException("Notification publish was interrupted", ex);
        } catch (ExecutionException ex) {
            throw new MessagePublishException("Notification publisher confirm failed", ex);
        } catch (TimeoutException ex) {
            throw new MessagePublishException("Notification publisher confirm timed out", ex);
        }
    }

    private void sleep(long backoffMs) {
        if (backoffMs <= 0) {
            return;
        }
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MessagePublishException("Notification publish retry sleep was interrupted", ex);
        }
    }
}
