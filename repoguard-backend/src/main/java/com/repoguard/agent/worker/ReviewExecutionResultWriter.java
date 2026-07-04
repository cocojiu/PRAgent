package com.repoguard.agent.worker;

import com.repoguard.agent.config.CacheEvictionService;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubPullRequestDiff;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.ReviewResult;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
class ReviewExecutionResultWriter {

    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewTaskClaimService claimService;
    private final ReviewTaskCompletionApplier completionApplier;
    private final ChangedFileReplacementService changedFileReplacementService;
    private final ReviewFindingReplacementService findingReplacementService;
    private final ReviewExecutionTimelineRecorder timelineRecorder;
    private final ReviewExecutionMetricsRecorder metricsRecorder;
    private final CacheEvictionService cacheEvictionService;

    ReviewExecutionResultWriter(
        ReviewTaskMapper reviewTaskMapper,
        ReviewTaskClaimService claimService,
        ReviewTaskCompletionApplier completionApplier,
        ChangedFileReplacementService changedFileReplacementService,
        ReviewFindingReplacementService findingReplacementService,
        ReviewExecutionTimelineRecorder timelineRecorder,
        ReviewExecutionMetricsRecorder metricsRecorder,
        CacheEvictionService cacheEvictionService
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.claimService = claimService;
        this.completionApplier = completionApplier;
        this.changedFileReplacementService = changedFileReplacementService;
        this.findingReplacementService = findingReplacementService;
        this.timelineRecorder = timelineRecorder;
        this.metricsRecorder = metricsRecorder;
        this.cacheEvictionService = cacheEvictionService;
    }

    WriteResult applyCompleted(
        ReviewTask task,
        GithubPullRequestDiff diff,
        ReviewResult reviewResult,
        LocalDateTime startedAt,
        String claimId
    ) {
        LocalDateTime finishedAt = LocalDateTime.now();
        boolean humanReviewRequired = completionApplier.applyCompleted(task, reviewResult, startedAt, finishedAt);
        claimService.ensureClaimOwnedAndFenceTerminalStatus(task, claimId);
        task.setReviewClaimedAt(null);
        task.setReviewClaimedBy(null);
        reviewTaskMapper.updateById(task);
        changedFileReplacementService.replace(task.getId(), diff);
        timelineRecorder.diffFetched(task, finishedAt);
        int findingCount = findingReplacementService.replace(task.getId(), reviewResult);
        timelineRecorder.reviewGenerated(task, reviewResult, finishedAt);
        timelineRecorder.reviewTerminal(task, humanReviewRequired, finishedAt);
        metricsRecorder.recordCompleted(reviewResult, startedAt, finishedAt);
        evictDashboardOverview();
        return new WriteResult(findingCount, humanReviewRequired);
    }

    private void evictDashboardOverview() {
        if (cacheEvictionService != null) {
            cacheEvictionService.evictDashboardOverview();
        }
    }

    record WriteResult(int findingCount, boolean humanReviewRequired) {
    }
}
