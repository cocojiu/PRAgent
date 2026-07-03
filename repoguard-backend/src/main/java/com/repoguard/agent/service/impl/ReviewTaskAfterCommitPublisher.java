package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.messaging.MessagePublishException;
import com.repoguard.agent.messaging.MessagePublishFailureSanitizer;
import com.repoguard.agent.messaging.ReviewTaskMessage;
import com.repoguard.agent.messaging.ReviewTaskPublisher;
import com.repoguard.agent.review.LlmStatus;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class ReviewTaskAfterCommitPublisher {

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTimelineMapper reviewTimelineMapper;
    private final ReviewTaskPublisher reviewTaskPublisher;
    private final ReviewTaskStateMachine reviewTaskStateMachine;
    private final Executor reviewPublishExecutor;

    @Autowired
    public ReviewTaskAfterCommitPublisher(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewTaskPublisher reviewTaskPublisher,
        ReviewTaskStateMachine reviewTaskStateMachine,
        ReviewTaskAfterCommitPublisherExecutor reviewPublishExecutor
    ) {
        this(
            reviewTaskMapper,
            reviewTimelineMapper,
            reviewTaskPublisher,
            reviewTaskStateMachine,
            (Executor) reviewPublishExecutor
        );
    }

    ReviewTaskAfterCommitPublisher(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewTaskPublisher reviewTaskPublisher,
        ReviewTaskStateMachine reviewTaskStateMachine,
        Executor reviewPublishExecutor
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.reviewTimelineMapper = reviewTimelineMapper;
        this.reviewTaskPublisher = reviewTaskPublisher;
        this.reviewTaskStateMachine = reviewTaskStateMachine == null
            ? new ReviewTaskStateMachine()
            : reviewTaskStateMachine;
        this.reviewPublishExecutor = reviewPublishExecutor == null ? Runnable::run : reviewPublishExecutor;
    }

    public boolean publishAfterCommit(ReviewTask task, ReviewTaskMessage message, LocalDateTime queuedAt) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return publishAndMarkFailure(task, message, queuedAt);
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                reviewPublishExecutor.execute(() -> publishAndMarkFailure(task, message, queuedAt));
            }
        });
        return true;
    }

    private boolean publishAndMarkFailure(ReviewTask task, ReviewTaskMessage message, LocalDateTime queuedAt) {
        try {
            reviewTaskPublisher.publish(message);
            return true;
        } catch (MessagePublishException ex) {
            markPublishFailed(task, ex, queuedAt);
            return false;
        }
    }

    public void markPublishFailed(ReviewTask task, MessagePublishException ex, LocalDateTime failedAt) {
        task.setStatus(reviewTaskStateMachine.statusWhenPublishFailed());
        task.setLlmStatus(LlmStatus.PENDING.code());
        clearLlmQuality(task);
        task.setPublishAttempts((task.getPublishAttempts() == null ? 0 : task.getPublishAttempts()) + 1);
        task.setNextPublishRetryAt(failedAt.plusSeconds(60));
        task.setLastPublishError(truncate(errorMessage(ex)));
        reviewTaskMapper.updateById(task);

        ReviewTimeline timeline = new ReviewTimeline();
        timeline.setTaskId(task.getId());
        timeline.setLabel(truncate("Message publish failed: " + errorMessage(ex)));
        timeline.setEventTime(failedAt);
        timeline.setStatus("FAILED");
        timeline.setSortOrder(nextTimelineSortOrder(task.getId()));
        reviewTimelineMapper.insert(timeline);
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

    private void clearLlmQuality(ReviewTask task) {
        task.setLlmProvider(null);
        task.setLlmModel(null);
        task.setLlmDurationMs(null);
        task.setLlmParseStatus(null);
        task.setLlmFallbackReason(null);
        task.setLlmPromptSummary(null);
    }

    private String errorMessage(Exception ex) {
        return MessagePublishFailureSanitizer.sanitize(ex);
    }

    private String truncate(String value) {
        return value.length() > 120 ? value.substring(0, 117) + "..." : value;
    }
}
