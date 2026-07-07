package com.repoguard.agent.notification;

import com.repoguard.agent.config.RabbitNotificationQueueProperties;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.messaging.MessagePublishException;
import com.repoguard.agent.messaging.RabbitPublishFailureClassifier;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
class NotificationEventPublishCoordinator {

    private final NotificationEventPublisher eventPublisher;
    private final RabbitNotificationQueueProperties properties;
    private final NotificationPublishFailurePolicy publishFailurePolicy;
    private final NotificationPublishEventStateUpdater publishEventStateUpdater;
    private final RabbitPublishFailureClassifier failureClassifier;

    NotificationEventPublishCoordinator(
        NotificationEventPublisher eventPublisher,
        RabbitNotificationQueueProperties properties,
        NotificationPublishFailurePolicy publishFailurePolicy,
        NotificationPublishEventStateUpdater publishEventStateUpdater,
        RabbitPublishFailureClassifier failureClassifier
    ) {
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.publishFailurePolicy = Objects.requireNonNull(publishFailurePolicy, "publishFailurePolicy");
        this.publishEventStateUpdater = Objects.requireNonNull(publishEventStateUpdater, "publishEventStateUpdater");
        this.failureClassifier = Objects.requireNonNull(failureClassifier, "failureClassifier");
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

    NotificationPublishResult publish(NotificationEvent event) {
        try {
            eventPublisher.publish(toMessage(event));
            publishEventStateUpdater.markPublished(event);
            return NotificationPublishResult.published();
        } catch (RuntimeException ex) {
            markPublishFailed(event, ex);
            return NotificationPublishResult.failed(failureReason(ex));
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

    private String failureReason(RuntimeException ex) {
        if (ex instanceof MessagePublishException publishException) {
            return failureClassifier.classify(publishException);
        }
        return "publish_failed";
    }
}
