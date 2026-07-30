package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubPullRequestHeadChangedException;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class ReviewExecutionSupersededHandler {

    private final ReviewExecutionTaskTerminalWriter taskTerminalWriter;
    private final ReviewExecutionTimelineRecorder timelineRecorder;
    private final ReviewExecutionMetricsRecorder metricsRecorder;
    private final ReviewExecutionCacheInvalidator cacheInvalidator;

    ReviewExecutionSupersededHandler(
        ReviewExecutionTaskTerminalWriter taskTerminalWriter,
        ReviewExecutionTimelineRecorder timelineRecorder,
        ReviewExecutionMetricsRecorder metricsRecorder,
        ReviewExecutionCacheInvalidator cacheInvalidator
    ) {
        this.taskTerminalWriter = Objects.requireNonNull(taskTerminalWriter, "taskTerminalWriter");
        this.timelineRecorder = Objects.requireNonNull(timelineRecorder, "timelineRecorder");
        this.metricsRecorder = Objects.requireNonNull(metricsRecorder, "metricsRecorder");
        this.cacheInvalidator = Objects.requireNonNull(cacheInvalidator, "cacheInvalidator");
    }

    boolean applySuperseded(
        ReviewTask task,
        LocalDateTime startedAt,
        String claimId,
        GithubPullRequestHeadChangedException ex
    ) {
        ReviewExecutionTaskTerminalWriter.SupersededTaskWrite taskWrite =
            taskTerminalWriter.applySuperseded(task, startedAt, claimId, ex);
        if (!taskWrite.applied()) {
            return false;
        }
        timelineRecorder.reviewSuperseded(task, ex, taskWrite.supersededAt());
        metricsRecorder.recordSuperseded(startedAt, taskWrite.supersededAt());
        cacheInvalidator.reviewTaskChanged();
        return true;
    }
}
