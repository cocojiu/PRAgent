package com.repoguard.agent.notification.publish;

import com.repoguard.agent.config.RabbitNotificationQueueProperties;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.messaging.MessagePublishException;
import com.repoguard.agent.messaging.RabbitPublishFailureClassifier;
import com.repoguard.agent.notification.NotificationEventMessage;
import com.repoguard.agent.notification.NotificationEventPublisher;
import com.repoguard.agent.notification.outbox.NotificationOutboxEventStore;
import com.repoguard.agent.notification.outbox.NotificationPublishCompensationQuery;
import com.repoguard.agent.notification.outbox.NotificationPublishEventStateUpdater;
import com.repoguard.agent.notification.outbox.NotificationPublishFailureDecision;
import com.repoguard.agent.tenancy.TenantContext;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class NotificationEventPublishCoordinator {

    private static final Logger LOGGER = LoggerFactory.getLogger(
        "com.repoguard.agent.notification.NotificationEventPublishCoordinator"
    );

    private final NotificationEventPublisher eventPublisher;
    private final RabbitNotificationQueueProperties properties;
    private final NotificationPublishFailurePolicy publishFailurePolicy;
    private final NotificationPublishEventStateUpdater publishEventStateUpdater;
    private final RabbitPublishFailureClassifier failureClassifier;
    private final NotificationOutboxEventStore outboxEventStore;
    private final NotificationPublishCompensationQuery compensationQuery;
    private final NotificationPublishExecutor publishExecutor;
    private final String instanceId;

    @Autowired
    NotificationEventPublishCoordinator(
        NotificationEventPublisher eventPublisher,
        RabbitNotificationQueueProperties properties,
        NotificationPublishFailurePolicy publishFailurePolicy,
        NotificationPublishEventStateUpdater publishEventStateUpdater,
        RabbitPublishFailureClassifier failureClassifier,
        NotificationOutboxEventStore outboxEventStore,
        NotificationPublishCompensationQuery compensationQuery,
        NotificationPublishExecutor publishExecutor
    ) {
        this(
            eventPublisher,
            properties,
            publishFailurePolicy,
            publishEventStateUpdater,
            failureClassifier,
            outboxEventStore,
            compensationQuery,
            publishExecutor,
            "repoguard-notification-publish-" + UUID.randomUUID()
        );
    }

    NotificationEventPublishCoordinator(
        NotificationEventPublisher eventPublisher,
        RabbitNotificationQueueProperties properties,
        NotificationPublishFailurePolicy publishFailurePolicy,
        NotificationPublishEventStateUpdater publishEventStateUpdater,
        RabbitPublishFailureClassifier failureClassifier,
        NotificationOutboxEventStore outboxEventStore,
        NotificationPublishCompensationQuery compensationQuery,
        NotificationPublishExecutor publishExecutor,
        String instanceId
    ) {
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.publishFailurePolicy = Objects.requireNonNull(publishFailurePolicy, "publishFailurePolicy");
        this.publishEventStateUpdater = Objects.requireNonNull(publishEventStateUpdater, "publishEventStateUpdater");
        this.failureClassifier = Objects.requireNonNull(failureClassifier, "failureClassifier");
        this.outboxEventStore = Objects.requireNonNull(outboxEventStore, "outboxEventStore");
        this.compensationQuery = Objects.requireNonNull(compensationQuery, "compensationQuery");
        this.publishExecutor = Objects.requireNonNull(publishExecutor, "publishExecutor");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
    }

    public void publishAfterCommit(NotificationEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit(event);
                }
            });
            return;
        }
        submit(event);
    }

    NotificationPublishResult publish(NotificationEvent event) {
        LocalDateTime claimedAt = LocalDateTime.now();
        if (!outboxEventStore.claimForPublish(
            event,
            compensationQuery.claim(claimedAt, instanceId)
        )) {
            LOGGER.info(
                "Notification outbox publish skipped eventId={} eventKey={} operation=notification_publish result=claim_failed status={} retryCount={} nextRetryAt={}",
                event.getId(),
                event.getEventKey(),
                event.getStatus(),
                event.getRetryCount(),
                event.getNextRetryAt()
            );
            return NotificationPublishResult.skipped();
        }
        try {
            eventPublisher.publishOnce(toMessage(event));
            publishEventStateUpdater.markPublished(event);
            return NotificationPublishResult.published();
        } catch (RuntimeException ex) {
            markPublishFailed(event, ex);
            return NotificationPublishResult.failed(failureReason(ex));
        }
    }

    private void submit(NotificationEvent event) {
        try {
            publishExecutor.execute(() -> publish(event));
        } catch (RejectedExecutionException ex) {
            LOGGER.warn(
                "Notification outbox dispatch rejected eventId={} eventKey={} operation=notification_publish_dispatch result=executor_rejected",
                event == null ? null : event.getId(),
                event == null ? null : event.getEventKey()
            );
        }
    }

    private NotificationEventMessage toMessage(NotificationEvent event) {
        return new NotificationEventMessage(
            event.getId(),
            event.getEventKey(),
            event.getEventType(),
            event.getTaskId(),
            event.getBatchId(),
            TenantContext.currentTenantId(),
            event.getTraceId()
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
