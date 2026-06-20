package com.repoguard.agent.notification;

import com.repoguard.agent.config.RabbitNotificationQueueProperties;
import com.repoguard.agent.entity.NotificationEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
class NotificationEventPublishCoordinator {

    private final NotificationEventPublisher eventPublisher;
    private final RabbitNotificationQueueProperties properties;
    private final NotificationPublishFailurePolicy publishFailurePolicy;
    private final NotificationPublishEventStateUpdater publishEventStateUpdater;

    NotificationEventPublishCoordinator(
        NotificationEventPublisher eventPublisher,
        RabbitNotificationQueueProperties properties,
        NotificationPublishFailurePolicy publishFailurePolicy,
        NotificationPublishEventStateUpdater publishEventStateUpdater
    ) {
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.publishFailurePolicy = publishFailurePolicy;
        this.publishEventStateUpdater = publishEventStateUpdater;
    }

    void publishAfterCommit(NotificationEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish(event);
                }
            });
            return;
        }
        publish(event);
    }

    void publish(NotificationEvent event) {
        try {
            eventPublisher.publish(toMessage(event));
            publishEventStateUpdater.markPublished(event);
        } catch (RuntimeException ex) {
            markPublishFailed(event, ex);
        }
    }

    private NotificationEventMessage toMessage(NotificationEvent event) {
        return new NotificationEventMessage(
            event.getId(),
            event.getEventKey(),
            event.getEventType(),
            event.getTaskId(),
            event.getBatchId()
        );
    }

    private void markPublishFailed(NotificationEvent event, RuntimeException ex) {
        NotificationPublishFailureDecision decision = publishFailurePolicy.decide(
            event,
            ex,
            properties.getPublishCompensationMaxAttempts()
        );
        publishEventStateUpdater.markPublishFailed(event, decision);
    }
}
