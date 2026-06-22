package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.CacheEvictionService;
import com.repoguard.agent.dto.ReviewRetryResponse;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.messaging.MessagePublishException;
import com.repoguard.agent.messaging.ReviewTaskMessage;
import com.repoguard.agent.observability.LogContext;
import com.repoguard.agent.review.HumanReviewStatus;
import com.repoguard.agent.review.LlmStatus;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
public class ReviewTaskRetryService {

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTimelineMapper reviewTimelineMapper;
    private final ReviewTaskStateMachine reviewTaskStateMachine;
    private final ReviewTaskAfterCommitPublisher reviewTaskAfterCommitPublisher;
    private final CacheEvictionService cacheEvictionService;

    public ReviewTaskRetryService(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewTaskStateMachine reviewTaskStateMachine,
        ReviewTaskAfterCommitPublisher reviewTaskAfterCommitPublisher,
        CacheEvictionService cacheEvictionService
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.reviewTimelineMapper = reviewTimelineMapper;
        this.reviewTaskStateMachine = reviewTaskStateMachine == null
            ? new ReviewTaskStateMachine()
            : reviewTaskStateMachine;
        this.reviewTaskAfterCommitPublisher = reviewTaskAfterCommitPublisher;
        this.cacheEvictionService = cacheEvictionService;
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

        insertRetryTimeline(task.getId(), queuedAt);
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

    private void insertRetryTimeline(Long taskId, LocalDateTime queuedAt) {
        reviewTimelineMapper.update(
            new UpdateWrapper<ReviewTimeline>()
                .eq("task_id", taskId)
                .eq("status", "CURRENT")
                .set("status", "DONE")
        );

        ReviewTimeline timeline = new ReviewTimeline();
        timeline.setTaskId(taskId);
        timeline.setLabel("Retry queued");
        timeline.setEventTime(queuedAt);
        timeline.setStatus("CURRENT");
        timeline.setSortOrder(nextTimelineSortOrder(taskId));
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

    private void evictDashboardOverview() {
        if (cacheEvictionService != null) {
            cacheEvictionService.evictDashboardOverview();
        }
    }
}
