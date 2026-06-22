package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.RabbitReviewQueueProperties;
import com.repoguard.agent.dto.MessageQueueRequeueResponse;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.messaging.MessagePublishException;
import com.repoguard.agent.messaging.ReviewTaskMessage;
import com.repoguard.agent.messaging.ReviewTaskPublisher;
import com.repoguard.agent.observability.LogContext;
import com.repoguard.agent.review.LlmStatus;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class ReviewTaskRequeueService {

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTimelineMapper reviewTimelineMapper;
    private final RabbitReviewQueueProperties properties;
    private final ReviewTaskPublisher reviewTaskPublisher;
    private final ReviewTaskStateMachine reviewTaskStateMachine;
    private final MessageQueueAuditRecorder auditRecorder;
    private final TransactionTemplate transactionTemplate;

    ReviewTaskRequeueService(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        RabbitReviewQueueProperties properties,
        ReviewTaskPublisher reviewTaskPublisher,
        PlatformTransactionManager transactionManager,
        ReviewTaskStateMachine reviewTaskStateMachine,
        MessageQueueAuditRecorder auditRecorder
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.reviewTimelineMapper = reviewTimelineMapper;
        this.properties = properties;
        this.reviewTaskPublisher = reviewTaskPublisher;
        this.reviewTaskStateMachine = reviewTaskStateMachine == null
            ? new ReviewTaskStateMachine()
            : reviewTaskStateMachine;
        this.auditRecorder = auditRecorder;
        this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
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
        appendTimeline(task.getId(), "Message manually requeued", queuedAt, "CURRENT");

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
        if (transactionTemplate == null) {
            return callback.execute();
        }
        return transactionTemplate.execute(status -> callback.execute());
    }

    private void appendTimeline(Long taskId, String label, LocalDateTime eventTime, String status) {
        reviewTimelineMapper.update(
            new UpdateWrapper<ReviewTimeline>()
                .eq("task_id", taskId)
                .eq("status", "CURRENT")
                .set("status", "DONE")
        );

        ReviewTimeline timeline = new ReviewTimeline();
        timeline.setTaskId(taskId);
        timeline.setLabel(truncate(label));
        timeline.setEventTime(eventTime);
        timeline.setStatus(status);
        timeline.setSortOrder(nextTimelineSortOrder(taskId));
        reviewTimelineMapper.insert(timeline);
    }

    private void markPublishFailed(ReviewTask task, MessagePublishException ex, LocalDateTime failedAt) {
        task.setStatus(reviewTaskStateMachine.statusWhenPublishFailed());
        task.setLlmStatus(LlmStatus.PENDING.code());
        task.setPublishAttempts(safeAttempts(task) + 1);
        task.setNextPublishRetryAt(failedAt.plusNanos(Math.max(1000, properties.getPublishCompensationIntervalMs()) * 1_000_000));
        task.setLastPublishError(truncate(errorMessage(ex)));
        task.setPublishClaimedAt(null);
        task.setPublishClaimedBy(null);
        reviewTaskMapper.updateById(task);
        appendTimeline(task.getId(), "Message manual requeue failed: " + errorMessage(ex), failedAt, "FAILED");
    }

    private int nextTimelineSortOrder(Long taskId) {
        ReviewTimeline latest = reviewTimelineMapper.selectOne(
            new LambdaQueryWrapper<ReviewTimeline>()
                .eq(ReviewTimeline::getTaskId, taskId)
                .orderByDesc(ReviewTimeline::getSortOrder)
                .last("limit 1")
        );
        return latest == null || latest.getSortOrder() == null ? 1 : latest.getSortOrder() + 1;
    }

    private int safeAttempts(ReviewTask task) {
        return task.getPublishAttempts() == null ? 0 : task.getPublishAttempts();
    }

    private String errorMessage(Exception ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank()
            ? ex.getClass().getSimpleName()
            : ex.getMessage().replaceAll("\\s+", " ").trim();
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
