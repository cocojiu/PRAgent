package com.repoguard.agent.review.task;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.cache.CacheEvictionService;
import com.repoguard.agent.dto.ReviewRetryResponse;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.observability.LogContext;
import com.repoguard.agent.review.PullRequestHeadProvider;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.timeline.ReviewTimelineAppender;
import com.repoguard.agent.timeline.ReviewTimelineStatus;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ReviewTaskRetryService {

    private final ReviewTaskTransitionStore transitionStore;
    private final ReviewTimelineAppender reviewTimelineAppender;
    private final ReviewTaskStateMachine reviewTaskStateMachine;
    private final ReviewTaskAfterCommitPublisher reviewTaskAfterCommitPublisher;
    private final CacheEvictionService cacheEvictionService;
    private final PullRequestHeadProvider pullRequestHeadProvider;

    @Autowired
    public ReviewTaskRetryService(
        ReviewTaskTransitionStore transitionStore,
        ReviewTimelineAppender reviewTimelineAppender,
        ReviewTaskStateMachine reviewTaskStateMachine,
        ReviewTaskAfterCommitPublisher reviewTaskAfterCommitPublisher,
        CacheEvictionService cacheEvictionService,
        PullRequestHeadProvider pullRequestHeadProvider
    ) {
        this.transitionStore = Objects.requireNonNull(transitionStore, "transitionStore");
        this.reviewTimelineAppender = reviewTimelineAppender;
        this.reviewTaskStateMachine = Objects.requireNonNull(reviewTaskStateMachine, "reviewTaskStateMachine");
        this.reviewTaskAfterCommitPublisher = reviewTaskAfterCommitPublisher;
        this.cacheEvictionService = Objects.requireNonNull(cacheEvictionService, "cacheEvictionService");
        this.pullRequestHeadProvider = Objects.requireNonNull(
            pullRequestHeadProvider,
            "pullRequestHeadProvider"
        );
    }

    public ReviewRetryResponse retry(Long id) {
        ReviewTask task = transitionStore.findById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + id);
        }
        reviewTaskStateMachine.ensureRetryAllowed(task.getStatus());

        LocalDateTime queuedAt = LocalDateTime.now();
        int retryCount = task.getMqRetries() == null ? 1 : task.getMqRetries() + 1;
        boolean superseded = reviewTaskStateMachine.isSuperseded(task.getStatus());
        String replacementCommitSha = superseded
            ? pullRequestHeadProvider.fetchPullRequestHeadSha(task)
            : task.getCommitSha();
        if (!StringUtils.hasText(replacementCommitSha)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Review task commit SHA is unavailable");
        }
        transitionStore.retryReviewTask(task, retryCount, replacementCommitSha);
        evictDashboardReviewActivity(task);

        reviewTimelineAppender.completeCurrentAndAppend(
            task.getId(),
            superseded ? "Retry queued for latest pull request head" : "Retry queued",
            queuedAt,
            ReviewTimelineStatus.CURRENT
        );
        ReviewTaskMessage message = new ReviewTaskMessage(
            task.getId(),
            task.getOrganization(),
            task.getRepository(),
            task.getPrNumber(),
            task.getCommitSha(),
            queuedAt,
            LogContext.currentTraceId(),
            8
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
        } catch (ReviewTaskPublishException ex) {
            reviewTaskAfterCommitPublisher.markPublishFailed(task, ex, queuedAt);
            return new ReviewRetryResponse(
                task.getId(),
                "publish_failed",
                "Review task saved, waiting for message publish compensation",
                retryCount
            );
        }
    }

    /** Re-runs a terminal Check Run after GitHub sends the built-in rerequested event. */
    public ReviewRetryResponse rerunFromGithubCheck(Long id, String requestedHeadSha) {
        ReviewTask task = transitionStore.findById(id);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + id);
        }
        reviewTaskStateMachine.ensureGithubCheckRerunAllowed(task.getStatus());
        if (!reviewTaskStateMachine.isSuperseded(task.getStatus())
            && StringUtils.hasText(requestedHeadSha)
            && !requestedHeadSha.trim().equalsIgnoreCase(task.getCommitSha())) {
            throw new BusinessException(ErrorCode.CONFLICT, "GitHub Check Run head SHA does not match the review task");
        }
        LocalDateTime queuedAt = LocalDateTime.now();
        int retryCount = task.getMqRetries() == null ? 1 : task.getMqRetries() + 1;
        String replacementCommitSha = reviewTaskStateMachine.isSuperseded(task.getStatus())
            ? pullRequestHeadProvider.fetchPullRequestHeadSha(task)
            : task.getCommitSha();
        if (!StringUtils.hasText(replacementCommitSha)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Review task commit SHA is unavailable");
        }
        transitionStore.retryReviewTask(task, retryCount, replacementCommitSha);
        evictDashboardReviewActivity(task);
        reviewTimelineAppender.completeCurrentAndAppend(
            task.getId(), "GitHub Check Run requested a new review", queuedAt, ReviewTimelineStatus.CURRENT
        );
        ReviewTaskMessage message = new ReviewTaskMessage(
            task.getId(), task.getOrganization(), task.getRepository(), task.getPrNumber(),
            task.getCommitSha(), queuedAt, LogContext.currentTraceId(), 4
        );
        try {
            boolean queued = reviewTaskAfterCommitPublisher.publishAfterCommit(task, message, queuedAt);
            return new ReviewRetryResponse(
                task.getId(), queued ? "queued" : "publish_failed",
                queued ? "Review task queued for GitHub Check Run rerun" : "Review task saved, waiting for message publish compensation",
                retryCount
            );
        } catch (ReviewTaskPublishException ex) {
            reviewTaskAfterCommitPublisher.markPublishFailed(task, ex, queuedAt);
            return new ReviewRetryResponse(
                task.getId(), "publish_failed", "Review task saved, waiting for message publish compensation", retryCount
            );
        }
    }

    private void evictDashboardReviewActivity(ReviewTask task) {
        cacheEvictionService.evictDashboardReviewActivity(task.getCreatedAt().toLocalDate());
    }
}
