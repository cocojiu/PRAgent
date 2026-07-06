package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubPullRequestDiff;
import com.repoguard.agent.review.ReviewResult;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
class ReviewExecutionResultWriter {

    private final ReviewExecutionTaskTerminalWriter taskTerminalWriter;
    private final ChangedFileReplacementService changedFileReplacementService;
    private final ReviewFindingReplacementService findingReplacementService;
    private final ReviewExecutionTimelineRecorder timelineRecorder;
    private final ReviewExecutionMetricsRecorder metricsRecorder;
    private final ReviewExecutionCacheInvalidator cacheInvalidator;

    ReviewExecutionResultWriter(
        ReviewExecutionTaskTerminalWriter taskTerminalWriter,
        ChangedFileReplacementService changedFileReplacementService,
        ReviewFindingReplacementService findingReplacementService,
        ReviewExecutionTimelineRecorder timelineRecorder,
        ReviewExecutionMetricsRecorder metricsRecorder,
        ReviewExecutionCacheInvalidator cacheInvalidator
    ) {
        this.taskTerminalWriter = taskTerminalWriter;
        this.changedFileReplacementService = changedFileReplacementService;
        this.findingReplacementService = findingReplacementService;
        this.timelineRecorder = timelineRecorder;
        this.metricsRecorder = metricsRecorder;
        this.cacheInvalidator = cacheInvalidator;
    }

    WriteResult applyCompleted(
        ReviewTask task,
        GithubPullRequestDiff diff,
        ReviewResult reviewResult,
        LocalDateTime startedAt,
        String claimId
    ) {
        ReviewExecutionTaskTerminalWriter.CompletedTaskWrite taskWrite =
            taskTerminalWriter.applyCompleted(task, reviewResult, startedAt, claimId);
        changedFileReplacementService.replace(task.getId(), diff);
        timelineRecorder.diffFetched(task, taskWrite.finishedAt());
        int findingCount = findingReplacementService.replace(task.getId(), reviewResult);
        timelineRecorder.reviewGenerated(task, reviewResult, taskWrite.finishedAt());
        timelineRecorder.reviewTerminal(task, taskWrite.humanReviewRequired(), taskWrite.finishedAt());
        metricsRecorder.recordCompleted(reviewResult, startedAt, taskWrite.finishedAt());
        cacheInvalidator.reviewTaskChanged();
        return new WriteResult(findingCount, taskWrite.humanReviewRequired());
    }

    record WriteResult(int findingCount, boolean humanReviewRequired) {
    }
}
