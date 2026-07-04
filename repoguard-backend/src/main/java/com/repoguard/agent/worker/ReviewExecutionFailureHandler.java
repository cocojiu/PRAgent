package com.repoguard.agent.worker;

import com.repoguard.agent.config.CacheEvictionService;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalCallException;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
public class ReviewExecutionFailureHandler {

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTaskClaimService claimService;
    private final ReviewTaskCompletionApplier completionApplier;
    private final ReviewExecutionTimelineRecorder timelineRecorder;
    private final ReviewExecutionMetricsRecorder metricsRecorder;
    private final CacheEvictionService cacheEvictionService;

    public ReviewExecutionFailureHandler(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTaskClaimService claimService,
        ReviewTaskCompletionApplier completionApplier,
        ReviewExecutionTimelineRecorder timelineRecorder,
        ReviewExecutionMetricsRecorder metricsRecorder,
        CacheEvictionService cacheEvictionService
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.claimService = claimService;
        this.completionApplier = completionApplier == null
            ? new ReviewTaskCompletionApplier(null)
            : completionApplier;
        this.timelineRecorder = timelineRecorder;
        this.metricsRecorder = metricsRecorder;
        this.cacheEvictionService = cacheEvictionService;
    }

    public boolean applyFailure(ReviewTask task, LocalDateTime startedAt, String claimId, RuntimeException ex) {
        LocalDateTime failedAt = LocalDateTime.now();
        completionApplier.applyFailed(task, startedAt, failedAt);
        if (!claimService.fenceTerminalStatus(task, claimId)) {
            return false;
        }
        task.setReviewClaimedAt(null);
        task.setReviewClaimedBy(null);
        reviewTaskMapper.updateById(task);
        timelineRecorder.reviewFailed(task, ex, failedAt);
        metricsRecorder.recordFailed(ex, startedAt, failedAt);
        evictDashboardOverview();
        return true;
    }

    public String failureCategory(RuntimeException ex) {
        if (ex instanceof ExternalCallException externalCallException) {
            return externalCallException.getCategory();
        }
        return ex.getClass().getSimpleName();
    }

    private void evictDashboardOverview() {
        if (cacheEvictionService != null) {
            cacheEvictionService.evictDashboardOverview();
        }
    }
}
