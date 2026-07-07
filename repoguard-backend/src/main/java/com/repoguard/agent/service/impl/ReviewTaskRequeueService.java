package com.repoguard.agent.service.impl;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.RabbitReviewQueueProperties;
import com.repoguard.agent.dto.MessageQueueRequeueResponse;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.messaging.MessagePublishException;
import com.repoguard.agent.messaging.MessagePublishFailureSanitizer;
import com.repoguard.agent.messaging.ReviewTaskMessage;
import com.repoguard.agent.messaging.ReviewTaskPublishOutboxStore;
import com.repoguard.agent.messaging.ReviewTaskPublisher;
import com.repoguard.agent.observability.LogContext;
import com.repoguard.agent.review.LlmStatus;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class ReviewTaskRequeueService {

    private final ReviewTaskMapper reviewTaskMapper;
    private final RabbitReviewQueueProperties properties;
    private final ReviewTaskPublisher reviewTaskPublisher;
    private final ReviewTaskStateMachine reviewTaskStateMachine;
    private final MessageQueueAuditRecorder auditRecorder;
    private final ReviewTaskPublishOutboxStore outboxStore;
    private final TransactionTemplate transactionTemplate;

    ReviewTaskRequeueService(
        ReviewTaskMapper reviewTaskMapper,
        RabbitReviewQueueProperties properties,
        ReviewTaskPublisher reviewTaskPublisher,
        PlatformTransactionManager transactionManager,
        ReviewTaskStateMachine reviewTaskStateMachine,
        MessageQueueAuditRecorder auditRecorder,
        ReviewTaskPublishOutboxStore outboxStore
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.properties = properties;
        this.reviewTaskPublisher = reviewTaskPublisher;
        this.reviewTaskStateMachine = Objects.requireNonNull(reviewTaskStateMachine, "reviewTaskStateMachine");
        this.auditRecorder = auditRecorder;
        this.outboxStore = Objects.requireNonNull(outboxStore, "outboxStore");
        this.transactionTemplate = new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "transactionManager")
        );
    }

    MessageQueueRequeueResponse requeueTask(Long taskId) {
        RequeuePublishContext context = executeInTransaction(() -> prepareRequeue(taskId));

        try {
            reviewTaskPublisher.publish(context.message());
            auditRecorder.recordRequeue(context.taskId(), "SUCCESS", "queued");
            return new MessageQueueRequeueResponse(context.taskId(), "queued", "Message task requeued", context.publishAttempts());
        } catch (MessagePublishException ex) {
            executeInTransaction(() -> {
                ReviewTask failedTask = reviewTaskMapper.selectById(context.taskId());
                if (failedTask != null) {
                    markPublishFailed(failedTask, ex, context.queuedAt());
                }
                return null;
            });
            auditRecorder.recordRequeue(context.taskId(), "FAILED", truncate(errorMessage(ex)));
            return new MessageQueueRequeueResponse(
                context.taskId(),
                "publish_failed",
                "Message task saved, waiting for publish compensation",
                context.publishAttempts() + 1
            );
        }
    }

    private RequeuePublishContext prepareRequeue(Long taskId) {
        ReviewTask task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            auditRecorder.recordRequeue(taskId, "FAILED", "not found");
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + taskId);
        }
        try {
            reviewTaskStateMachine.ensurePublishRequeueAllowed(task.getStatus(), task.getPublishClaimedAt() != null);
        } catch (BusinessException ex) {
            if (task.getPublishClaimedAt() != null) {
                auditRecorder.recordRequeue(taskId, "FAILED", "claimedBy=" + task.getPublishClaimedBy());
            } else {
                auditRecorder.recordRequeue(taskId, "FAILED", "status=" + task.getStatus());
            }
            throw ex;
        }

        LocalDateTime queuedAt = LocalDateTime.now();
        task.setStatus(reviewTaskStateMachine.statusWhenQueued());
        task.setLlmStatus(LlmStatus.PENDING.code());
        task.setPublishAttempts(0);
        task.setNextPublishRetryAt(null);
        task.setLastPublishError(null);
        task.setPublishClaimedAt(null);
        task.setPublishClaimedBy(null);
        reviewTaskMapper.updateById(task);
        outboxStore.markCurrentTimelinesDone(task.getId());
        outboxStore.appendTimeline(task.getId(), "Message manually requeued", queuedAt, "CURRENT");

        return new RequeuePublishContext(
            task.getId(),
            task.getPublishAttempts(),
            queuedAt,
            new ReviewTaskMessage(
                task.getId(),
                task.getOrganization(),
                task.getRepository(),
                task.getPrNumber(),
                task.getCommitSha(),
                queuedAt,
                LogContext.currentTraceId()
            )
        );
    }

    private <T> T executeInTransaction(TransactionCallback<T> callback) {
        return transactionTemplate.execute(status -> callback.execute());
    }

    private void markPublishFailed(ReviewTask task, MessagePublishException ex, LocalDateTime failedAt) {
        outboxStore.markDirectPublishFailed(
            task,
            ex,
            failedAt,
            Math.max(1000, properties.getPublishCompensationIntervalMs()),
            "Message manual requeue failed: ",
            false,
            true
        );
    }

    private String errorMessage(Exception ex) {
        return MessagePublishFailureSanitizer.sanitize(ex);
    }

    private String truncate(String value) {
        return value.length() > 120 ? value.substring(0, 117) + "..." : value;
    }

    private record RequeuePublishContext(
        Long taskId,
        int publishAttempts,
        LocalDateTime queuedAt,
        ReviewTaskMessage message
    ) {
    }

    @FunctionalInterface
    private interface TransactionCallback<T> {
        T execute();
    }
}
