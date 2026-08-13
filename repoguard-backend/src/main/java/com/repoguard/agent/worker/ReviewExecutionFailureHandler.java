package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewTask;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class ReviewExecutionFailureHandler {

    private final ReviewExecutionTaskTerminalWriter taskTerminalWriter;
    private final ReviewExecutionTimelineRecorder timelineRecorder;
    private final ReviewExecutionMetricsRecorder metricsRecorder;
    private final ReviewExecutionCacheInvalidator cacheInvalidator;
    private final ReviewExecutionFailureClassifier failureClassifier;

    public ReviewExecutionFailureHandler(
        ReviewExecutionTaskTerminalWriter taskTerminalWriter,
        ReviewExecutionTimelineRecorder timelineRecorder,
        ReviewExecutionMetricsRecorder metricsRecorder,
        ReviewExecutionCacheInvalidator cacheInvalidator,
        ReviewExecutionFailureClassifier failureClassifier
    ) {
        this.taskTerminalWriter = Objects.requireNonNull(taskTerminalWriter, "taskTerminalWriter");
        this.timelineRecorder = timelineRecorder;
        this.metricsRecorder = metricsRecorder;
        this.cacheInvalidator = cacheInvalidator;
        this.failureClassifier = Objects.requireNonNull(failureClassifier, "failureClassifier");
    }

    public boolean applyFailure(ReviewTask task, LocalDateTime startedAt, String claimId, RuntimeException ex) {
        ReviewExecutionTaskTerminalWriter.FailedTaskWrite taskWrite =
            taskTerminalWriter.applyFailed(task, startedAt, claimId);
        if (!taskWrite.applied()) {
            return false;
        }
        timelineRecorder.reviewFailed(task, ex, taskWrite.failedAt());
        metricsRecorder.recordFailed(ex, startedAt, taskWrite.failedAt());
        cacheInvalidator.reviewTaskChanged(task);
        return true;
    }

    public String failureCategory(RuntimeException ex) {
        return failureClassifier.failureCategory(ex);
    }

}
