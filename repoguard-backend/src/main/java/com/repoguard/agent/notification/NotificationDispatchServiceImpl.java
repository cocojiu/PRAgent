package com.repoguard.agent.notification;

import com.repoguard.agent.dto.GithubCommentPublishResponse;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.entity.ReviewTask;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationDispatchServiceImpl implements NotificationDispatchService {

    private final NotificationOutboxEventStore outboxEventStore;
    private final NotificationEventPayloadBuilder payloadBuilder;
    private final NotificationEventPublishCoordinator publishCoordinator;

    public NotificationDispatchServiceImpl(
        NotificationOutboxEventStore outboxEventStore,
        NotificationEventPayloadBuilder payloadBuilder,
        NotificationEventPublishCoordinator publishCoordinator
    ) {
        this.outboxEventStore = outboxEventStore;
        this.payloadBuilder = payloadBuilder;
        this.publishCoordinator = publishCoordinator;
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
        publishCoordinator.publish(event);
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
        publishCoordinator.publishAfterCommit(event);
    }
}
