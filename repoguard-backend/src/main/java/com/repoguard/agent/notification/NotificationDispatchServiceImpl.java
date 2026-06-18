package com.repoguard.agent.notification;

import com.repoguard.agent.config.RabbitNotificationQueueProperties;
import com.repoguard.agent.dto.GithubCommentPublishResponse;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.entity.ReviewTask;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class NotificationDispatchServiceImpl implements NotificationDispatchService {

    private final NotificationOutboxEventStore outboxEventStore;
    private final NotificationEventPublisher eventPublisher;
    private final RabbitNotificationQueueProperties properties;
    private final NotificationEventPayloadBuilder payloadBuilder;
    private final NotificationPublishFailurePolicy publishFailurePolicy;
    private final NotificationPublishEventStateUpdater publishEventStateUpdater;

    public NotificationDispatchServiceImpl(
        NotificationOutboxEventStore outboxEventStore,
        NotificationEventPublisher eventPublisher,
        RabbitNotificationQueueProperties properties,
        NotificationEventPayloadBuilder payloadBuilder,
        NotificationPublishFailurePolicy publishFailurePolicy,
        NotificationPublishEventStateUpdater publishEventStateUpdater
    ) {
        this.outboxEventStore = outboxEventStore;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.payloadBuilder = payloadBuilder;
        this.publishFailurePolicy = publishFailurePolicy;
        this.publishEventStateUpdater = publishEventStateUpdater;
    }

    @Override
    @Transactional
    public void reviewFinished(ReviewTask task, int findingCount) {
        String eventType = Boolean.TRUE.equals(task.getHumanReviewRequired())
            ? NotificationEventType.HUMAN_REVIEW_REQUIRED.code()
            : NotificationEventType.REVIEW_COMPLETED.code();
        createAndPublish(eventType, task, null, findingCount, 0, 0, 0);
    }

    @Override
    @Transactional
    public void reviewFailed(ReviewTask task) {
        createAndPublish(NotificationEventType.REVIEW_FAILED.code(), task, null, 0, 0, 0, 0);
    }

    @Override
    @Transactional
    public void githubCommentsPublished(ReviewTask task, GithubCommentPublishResponse response, Long batchId) {
        createAndPublish(
            NotificationEventType.GITHUB_COMMENT_PUBLISHED.code(),
            task,
            batchId,
            response.totalFindings() == null ? 0 : response.totalFindings(),
            response.succeededCount() == null ? 0 : response.succeededCount(),
            response.failedCount() == null ? 0 : response.failedCount(),
            response.skippedCount() == null ? 0 : response.skippedCount()
        );
    }

    @Override
    public void publishExistingEvent(Long eventId) {
        NotificationEvent event = outboxEventStore.loadById(eventId);
        NotificationEventStatus eventStatus = event == null
            ? NotificationEventStatus.UNKNOWN
            : NotificationEventStatus.from(event.getStatus());
        if (event == null
            || NotificationEventStatus.DELIVERED == eventStatus
            || NotificationEventStatus.PUBLISHED == eventStatus) {
            return;
        }
        publishEvent(event);
    }

    private void createAndPublish(
        String eventType,
        ReviewTask task,
        Long batchId,
        int findingCount,
        int commentSucceededCount,
        int commentFailedCount,
        int commentSkippedCount
    ) {
        if (task == null || task.getId() == null) {
            return;
        }
        NotificationEventPayload payload = payloadBuilder.build(
            eventType,
            task,
            batchId,
            findingCount,
            commentSucceededCount,
            commentFailedCount,
            commentSkippedCount
        );
        NotificationEvent event = outboxEventStore.createPendingEvent(eventType, task, batchId, payload);
        NotificationEvent created = event;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishEvent(created);
                }
            });
        } else {
            publishEvent(created);
        }
    }

    private void publishEvent(NotificationEvent event) {
        try {
            eventPublisher.publish(new NotificationEventMessage(event.getId(), event.getEventKey(), event.getEventType(), event.getTaskId(), event.getBatchId()));
            publishEventStateUpdater.markPublished(event);
        } catch (RuntimeException ex) {
            markPublishFailed(event, ex);
        }
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
