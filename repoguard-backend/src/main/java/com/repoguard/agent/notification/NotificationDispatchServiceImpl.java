package com.repoguard.agent.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.config.RabbitNotificationQueueProperties;
import com.repoguard.agent.dto.GithubCommentPublishResponse;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.NotificationEventMapper;
import com.repoguard.agent.messaging.MessagePublishException;
import java.time.LocalDateTime;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class NotificationDispatchServiceImpl implements NotificationDispatchService {

    private static final int[] RETRY_MINUTES = {1, 5, 15, 30, 60};

    private final NotificationEventMapper eventMapper;
    private final NotificationEventPublisher eventPublisher;
    private final RabbitNotificationQueueProperties properties;
    private final ObjectMapper objectMapper;

    public NotificationDispatchServiceImpl(
        NotificationEventMapper eventMapper,
        NotificationEventPublisher eventPublisher,
        RabbitNotificationQueueProperties properties,
        ObjectMapper objectMapper
    ) {
        this.eventMapper = eventMapper;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void reviewFinished(ReviewTask task, int findingCount) {
        String eventType = Boolean.TRUE.equals(task.getHumanReviewRequired()) ? "HUMAN_REVIEW_REQUIRED" : "REVIEW_COMPLETED";
        createAndPublish(eventType, task, null, findingCount, 0, 0, 0);
    }

    @Override
    @Transactional
    public void reviewFailed(ReviewTask task) {
        createAndPublish("REVIEW_FAILED", task, null, 0, 0, 0, 0);
    }

    @Override
    @Transactional
    public void githubCommentsPublished(ReviewTask task, GithubCommentPublishResponse response, Long batchId) {
        createAndPublish(
            "GITHUB_COMMENT_PUBLISHED",
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
        NotificationEvent event = eventMapper.selectById(eventId);
        if (event == null || "DELIVERED".equalsIgnoreCase(event.getStatus()) || "PUBLISHED".equalsIgnoreCase(event.getStatus())) {
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
        NotificationMessage message = new NotificationMessage(
            eventType,
            task.getId(),
            batchId,
            task.getOrganization(),
            task.getRepository(),
            task.getPrNumber(),
            task.getTitle(),
            task.getStatus(),
            task.getRiskLevel(),
            findingCount,
            commentSucceededCount,
            commentFailedCount,
            commentSkippedCount,
            "/repoguard/tasks/" + task.getId()
        );
        NotificationEvent event = new NotificationEvent();
        LocalDateTime now = LocalDateTime.now();
        event.setEventKey(eventKey(eventType, task.getId(), batchId));
        event.setEventType(eventType);
        event.setTaskId(task.getId());
        event.setBatchId(batchId);
        event.setPayload(toJson(message));
        event.setStatus("PENDING");
        event.setRetryCount(0);
        event.setNextRetryAt(now);
        event.setCreatedAt(now);
        event.setUpdatedAt(now);
        try {
            eventMapper.insert(event);
        } catch (DuplicateKeyException ignored) {
            event = eventMapper.selectOne(new LambdaQueryWrapper<NotificationEvent>().eq(NotificationEvent::getEventKey, event.getEventKey()));
        }
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
            eventMapper.update(
                new UpdateWrapper<NotificationEvent>()
                    .eq("id", event.getId())
                    .ne("status", "DELIVERED")
                    .set("status", "PUBLISHED")
                    .set("last_error", null)
                    .set("updated_at", LocalDateTime.now())
            );
        } catch (RuntimeException ex) {
            markPublishFailed(event, ex);
        }
    }

    private void markPublishFailed(NotificationEvent event, RuntimeException ex) {
        int nextRetryCount = safe(event.getRetryCount()) + 1;
        boolean dead = nextRetryCount >= Math.max(1, properties.getPublishCompensationMaxAttempts());
        eventMapper.update(
            new UpdateWrapper<NotificationEvent>()
                .eq("id", event.getId())
                .ne("status", "DELIVERED")
                .set("status", dead ? "DEAD" : "PUBLISH_FAILED")
                .set("retry_count", nextRetryCount)
                .set("next_retry_at", dead ? null : nextRetryAt(nextRetryCount))
                .set("last_error", truncate(errorMessage(ex), 1024))
                .set("updated_at", LocalDateTime.now())
        );
    }

    private String eventKey(String eventType, Long taskId, Long batchId) {
        return batchId == null ? eventType + ":" + taskId : eventType + ":" + taskId + ":" + batchId;
    }

    private String toJson(NotificationMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException ex) {
            throw new MessagePublishException("Notification event payload serialization failed", ex);
        }
    }

    private LocalDateTime nextRetryAt(int retryCount) {
        int index = Math.min(Math.max(0, retryCount - 1), RETRY_MINUTES.length - 1);
        return LocalDateTime.now().plusMinutes(RETRY_MINUTES[index]);
    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }

    private String errorMessage(RuntimeException ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
