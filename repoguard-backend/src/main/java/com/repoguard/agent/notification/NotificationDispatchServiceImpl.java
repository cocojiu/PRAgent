package com.repoguard.agent.notification;

import com.repoguard.agent.service.NotificationDispatchService;
import com.repoguard.agent.dto.GithubCommentPublishResponse;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.entity.ReviewTask;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationDispatchServiceImpl implements NotificationDispatchService {

    private final NotificationOutboxEventStore outboxEventStore;
    private final NotificationEventPayloadBuilder payloadBuilder;
    private final NotificationEventPublishCoordinator publishCoordinator;
    private final NotificationDispatchRequestFactory requestFactory;

    @Autowired
    public NotificationDispatchServiceImpl(
        NotificationOutboxEventStore outboxEventStore,
        NotificationEventPayloadBuilder payloadBuilder,
        NotificationEventPublishCoordinator publishCoordinator,
        NotificationDispatchRequestFactory requestFactory
    ) {
        this.outboxEventStore = outboxEventStore;
        this.payloadBuilder = payloadBuilder;
        this.publishCoordinator = publishCoordinator;
        this.requestFactory = requestFactory;
    }

    @Override
    @Transactional
    public void reviewFinished(ReviewTask task, int findingCount) {
        createAndPublish(task, requestFactory.reviewFinished(task, findingCount));
    }

    @Override
    @Transactional
    public void reviewFailed(ReviewTask task) {
        createAndPublish(task, requestFactory.reviewFailed());
    }

    @Override
    @Transactional
    public void githubCommentsPublished(ReviewTask task, GithubCommentPublishResponse response, Long batchId) {
        createAndPublish(task, requestFactory.githubCommentsPublished(response, batchId));
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
        publishCoordinator.publishAfterCommit(event);
    }

    private void createAndPublish(ReviewTask task, NotificationDispatchRequest request) {
        if (task == null || task.getId() == null) {
            return;
        }
        NotificationEventPayload payload = payloadBuilder.build(
            request.eventType(),
            task,
            request.batchId(),
            request.findingCount(),
            request.commentSucceededCount(),
            request.commentFailedCount(),
            request.commentSkippedCount()
        );
        NotificationEvent event = outboxEventStore.createPendingEvent(request.eventType(), task, request.batchId(), payload);
        publishCoordinator.publishAfterCommit(event);
    }
}
