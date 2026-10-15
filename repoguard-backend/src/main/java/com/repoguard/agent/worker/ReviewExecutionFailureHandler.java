package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.external.ExternalCallException;
import com.repoguard.agent.external.ExternalHttpFailureDiagnostics;
import com.repoguard.agent.review.task.ReviewFailureSummaryResolver.ReviewFailureDetails;
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
    private final ObjectMapper objectMapper;

    public ReviewExecutionFailureHandler(
        ReviewExecutionTaskTerminalWriter taskTerminalWriter,
        ReviewExecutionTimelineRecorder timelineRecorder,
        ReviewExecutionMetricsRecorder metricsRecorder,
        ReviewExecutionCacheInvalidator cacheInvalidator,
        ReviewExecutionFailureClassifier failureClassifier,
        ObjectMapper objectMapper
    ) {
        this.taskTerminalWriter = Objects.requireNonNull(taskTerminalWriter, "taskTerminalWriter");
        this.timelineRecorder = timelineRecorder;
        this.metricsRecorder = metricsRecorder;
        this.cacheInvalidator = cacheInvalidator;
        this.failureClassifier = Objects.requireNonNull(failureClassifier, "failureClassifier");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public boolean applyFailure(ReviewTask task, LocalDateTime startedAt, String claimId, RuntimeException ex) {
        ExternalCallException external = ex instanceof ExternalCallException value ? value : null;
        String stage = external == null ? "REVIEW_EXECUTION"
            : "llm".equalsIgnoreCase(external.getSystem()) ? "LLM"
            : "github".equalsIgnoreCase(external.getSystem()) ? "GITHUB" : "EXTERNAL_CALL";
        String previousFailure = task.getFailureDetailsJson();
        task.setFailureDetailsJson(new ReviewFailureDetails(failureClassifier.failureCategory(ex),
            external == null ? null : external.isRetryable(), stage, task.getCurrentAttemptId(),
            ExternalHttpFailureDiagnostics.fromDetail(ex.getMessage()).retryAfter()).toJson(objectMapper));
        ReviewExecutionTaskTerminalWriter.FailedTaskWrite taskWrite =
            taskTerminalWriter.applyFailed(task, startedAt, claimId);
        if (!taskWrite.applied()) {
            task.setFailureDetailsJson(previousFailure);
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
