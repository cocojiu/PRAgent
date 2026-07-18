package com.repoguard.agent.observability;

import com.repoguard.agent.external.ExternalCallException;
import com.repoguard.agent.worker.ReviewExecutionFailureClassifier;
import java.time.Duration;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class ReviewMetricsRecorder {

    private final MetricRecorderSupport metrics;
    private final ReviewExecutionFailureClassifier failureClassifier;

    public ReviewMetricsRecorder(
        MetricRecorderSupport metrics,
        ReviewExecutionFailureClassifier failureClassifier
    ) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.failureClassifier = Objects.requireNonNull(failureClassifier, "failureClassifier");
    }

    void taskCreated(String source) {
        metrics.counter("repoguard.review.task.created", "source", metrics.normalize(source)).increment();
    }

    void taskCompleted(String riskLevel, String llmStatus) {
        metrics.counter(
            "repoguard.review.task.completed",
            "risk_level", metrics.normalize(riskLevel),
            "llm_status", metrics.normalize(llmStatus)
        ).increment();
    }

    void taskDuration(Duration duration, String result) {
        metrics.timer("repoguard.review.task.duration", "result", metrics.normalize(result))
            .record(metrics.nonNegative(duration));
    }

    void executionStageDuration(Duration duration, String stage, String result) {
        metrics.timer(
            "repoguard.review.execution.stage.duration",
            "stage", metrics.normalize(stage),
            "result", metrics.normalize(result)
        ).record(metrics.nonNegative(duration));
    }

    void taskFailed(RuntimeException ex) {
        metrics.counter(
            "repoguard.review.task.failed",
            "category", metrics.normalize(failureClassifier.failureCategory(ex)),
            "retryable", retryable(ex)
        ).increment();
    }

    void refreshTokenReuseDetected() {
        metrics.counter("repoguard.auth.refresh_token.reuse_detected").increment();
    }

    void refreshTokenConcurrentReplay() {
        metrics.counter("repoguard.auth.refresh_token.concurrent_replay").increment();
    }

    void dataRetentionCleanup(boolean executed, long candidateTasks, int selectedTasks, int deletedTasks) {
        String mode = executed ? "execute" : "dry_run";
        metrics.counter(
            "repoguard.data_retention.cleanup",
            "mode", mode,
            "result", "completed"
        ).increment();
        cleanupTasks(mode, "candidate", candidateTasks);
        cleanupTasks(mode, "selected", selectedTasks);
        cleanupTasks(mode, "deleted", deletedTasks);
    }

    void dataRetentionCleanupFailed(boolean executed, String reason) {
        metrics.counter(
            "repoguard.data_retention.cleanup.failed",
            "mode", executed ? "execute" : "dry_run",
            "reason", metrics.normalize(reason)
        ).increment();
    }

    private void cleanupTasks(String mode, String kind, long count) {
        metrics.summaryWithUnit(
            "repoguard.data_retention.cleanup.tasks",
            "tasks",
            "mode", mode,
            "kind", kind
        ).record(Math.max(0L, count));
    }

    private String retryable(RuntimeException ex) {
        if (ex instanceof ExternalCallException externalCallException) {
            return Boolean.toString(externalCallException.isRetryable());
        }
        return MetricRecorderSupport.UNKNOWN;
    }
}
