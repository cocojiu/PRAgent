package com.repoguard.agent.service.impl;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.CacheEvictionService;
import com.repoguard.agent.dto.ReviewRetryResponse;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.messaging.MessagePublishException;
import com.repoguard.agent.messaging.ReviewTaskMessage;
import com.repoguard.agent.observability.LogContext;
import com.repoguard.agent.review.HumanReviewStatus;
import com.repoguard.agent.review.LlmStatus;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.timeline.ReviewTimelineAppender;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ReviewTaskRetryService {

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTimelineAppender reviewTimelineAppender;
    private final ReviewTaskStateMachine reviewTaskStateMachine;
    private final ReviewTaskAfterCommitPublisher reviewTaskAfterCommitPublisher;
    private final CacheEvictionService cacheEvictionService;

    @Autowired
    public ReviewTaskRetryService(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineAppender reviewTimelineAppender,
        ReviewTaskStateMachine reviewTaskStateMachine,
        ReviewTaskAfterCommitPublisher reviewTaskAfterCommitPublisher,
        CacheEvictionService cacheEvictionService
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.reviewTimelineAppender = reviewTimelineAppender;
        this.reviewTaskStateMachine = Objects.requireNonNull(reviewTaskStateMachine, "reviewTaskStateMachine");
        this.reviewTaskAfterCommitPublisher = reviewTaskAfterCommitPublisher;
        this.cacheEvictionService = Objects.requireNonNull(cacheEvictionService, "cacheEvictionService");
    }

    public ReviewRetryResponse retry(Long id) {
        ReviewTask task = reviewTaskMapper.selectById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + id);
        }
        reviewTaskStateMachine.ensureRetryAllowed(task.getStatus());

        LocalDateTime queuedAt = LocalDateTime.now();
        int retryCount = task.getMqRetries() == null ? 1 : task.getMqRetries() + 1;
        resetTaskForRetry(task, retryCount);
        reviewTaskMapper.updateById(task);
        evictDashboardOverview();

        reviewTimelineAppender.completeCurrentAndAppend(task.getId(), "Retry queued", queuedAt, "CURRENT");
        ReviewTaskMessage message = new ReviewTaskMessage(
            task.getId(),
            task.getOrganization(),
            task.getRepository(),
            task.getPrNumber(),
            task.getCommitSha(),
            queuedAt,
            LogContext.currentTraceId()
        );
        try {
            boolean queued = reviewTaskAfterCommitPublisher.publishAfterCommit(task, message, queuedAt);
            if (queued) {
                return new ReviewRetryResponse(task.getId(), "queued", "Review task queued for retry", retryCount);
            }
            return new ReviewRetryResponse(
                task.getId(),
                "publish_failed",
                "Review task saved, waiting for message publish compensation",
                retryCount
            );
        } catch (MessagePublishException ex) {
            reviewTaskAfterCommitPublisher.markPublishFailed(task, ex, queuedAt);
            return new ReviewRetryResponse(
                task.getId(),
                "publish_failed",
                "Review task saved, waiting for message publish compensation",
                retryCount
            );
        }
    }

    private void resetTaskForRetry(ReviewTask task, int retryCount) {
        task.setStatus(reviewTaskStateMachine.statusWhenQueued());
        task.setRiskLevel("INFO");
        task.setMqRetries(retryCount);
        task.setPublishAttempts(0);
        task.setNextPublishRetryAt(null);
        task.setLastPublishError(null);
        task.setLlmStatus(LlmStatus.PENDING.code());
        clearLlmQuality(task);
        task.setHumanReviewRequired(false);
        task.setHumanReviewStatus(HumanReviewStatus.NOT_REQUIRED.code());
        task.setHumanReviewNote(null);
        task.setHumanReviewBy(null);
        task.setHumanReviewedAt(null);
        task.setDurationSeconds(0);
    }

    private void clearLlmQuality(ReviewTask task) {
        task.setLlmProvider(null);
        task.setLlmModel(null);
        task.setLlmDurationMs(null);
        task.setLlmParseStatus(null);
        task.setLlmFallbackReason(null);
        task.setLlmPromptSummary(null);
    }

    private void evictDashboardOverview() {
        cacheEvictionService.evictDashboardOverview();
    }
}
