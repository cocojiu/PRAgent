package com.repoguard.agent.worker;

import com.repoguard.agent.config.CacheEvictionService;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalCallException;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
public class ReviewExecutionFailureHandler {

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTaskClaimService claimService;
    private final ReviewTaskCompletionApplier completionApplier;
    private final ReviewTimelineAppender timelineAppender;
    private final RepoGuardMetrics metrics;
    private final CacheEvictionService cacheEvictionService;

    public ReviewExecutionFailureHandler(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTaskClaimService claimService,
        ReviewTaskCompletionApplier completionApplier,
        ReviewTimelineAppender timelineAppender,
        RepoGuardMetrics metrics,
        CacheEvictionService cacheEvictionService
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.claimService = claimService;
        this.completionApplier = completionApplier == null
            ? new ReviewTaskCompletionApplier(null)
            : completionApplier;
        this.timelineAppender = timelineAppender;
        this.metrics = metrics;
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
        timelineAppender.append(task.getId(), failureLabel(ex), failedAt, "FAILED", 5);
        if (metrics != null) {
            metrics.reviewTaskDuration(Duration.between(startedAt, failedAt), "failed");
        }
        evictDashboardOverview();
        return true;
    }

    public String failureCategory(RuntimeException ex) {
        if (ex instanceof ExternalCallException externalCallException) {
            return externalCallException.getCategory();
        }
        return ex.getClass().getSimpleName();
    }

    private String failureLabel(RuntimeException ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return "Review failed";
        }
        String normalized = message.replaceAll("\\s+", " ").trim();
        return truncateLabel("Review failed: " + normalized);
    }

    private String truncateLabel(String label) {
        return label.length() > 120 ? label.substring(0, 117) + "..." : label;
    }

    private void evictDashboardOverview() {
        if (cacheEvictionService != null) {
            cacheEvictionService.evictDashboardOverview();
        }
    }
}
