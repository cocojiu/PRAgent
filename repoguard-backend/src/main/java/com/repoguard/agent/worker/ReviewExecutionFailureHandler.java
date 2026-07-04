package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class ReviewExecutionFailureHandler {

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTaskClaimService claimService;
    private final ReviewTaskCompletionApplier completionApplier;
    private final ReviewExecutionTimelineRecorder timelineRecorder;
    private final ReviewExecutionMetricsRecorder metricsRecorder;
    private final ReviewExecutionCacheInvalidator cacheInvalidator;
    private final ReviewExecutionClock clock;
    private final ReviewExecutionFailureClassifier failureClassifier;

    public ReviewExecutionFailureHandler(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTaskClaimService claimService,
        ReviewTaskCompletionApplier completionApplier,
        ReviewExecutionTimelineRecorder timelineRecorder,
        ReviewExecutionMetricsRecorder metricsRecorder,
        ReviewExecutionCacheInvalidator cacheInvalidator,
        ReviewExecutionClock clock,
        ReviewExecutionFailureClassifier failureClassifier
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.claimService = claimService;
        this.completionApplier = Objects.requireNonNull(completionApplier, "completionApplier");
        this.timelineRecorder = timelineRecorder;
        this.metricsRecorder = metricsRecorder;
        this.cacheInvalidator = cacheInvalidator;
        this.clock = clock;
        this.failureClassifier = Objects.requireNonNull(failureClassifier, "failureClassifier");
    }

    public boolean applyFailure(ReviewTask task, LocalDateTime startedAt, String claimId, RuntimeException ex) {
        LocalDateTime failedAt = clock.now();
        completionApplier.applyFailed(task, startedAt, failedAt);
        if (!claimService.fenceTerminalStatus(task, claimId)) {
            return false;
        }
        claimService.releaseReviewClaim(task);
        reviewTaskMapper.updateById(task);
        timelineRecorder.reviewFailed(task, ex, failedAt);
        metricsRecorder.recordFailed(ex, startedAt, failedAt);
        cacheInvalidator.reviewTaskChanged();
        return true;
    }

    public String failureCategory(RuntimeException ex) {
        return failureClassifier.failureCategory(ex);
    }

}
