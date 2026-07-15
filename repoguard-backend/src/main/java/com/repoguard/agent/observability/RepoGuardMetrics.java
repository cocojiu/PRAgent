package com.repoguard.agent.observability;

import com.repoguard.agent.external.ExternalCallException;
import com.repoguard.agent.worker.ReviewExecutionFailureClassifier;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class RepoGuardMetrics {

    private final ReviewMetricsRecorder reviewMetrics;
    private final ExternalMetricsRecorder externalMetrics;
    private final ObservabilityMetricsRecorder observabilityMetrics;
    private final RabbitMetricsRecorder rabbitMetrics;

    public RepoGuardMetrics(
        ReviewMetricsRecorder reviewMetrics,
        ExternalMetricsRecorder externalMetrics,
        ObservabilityMetricsRecorder observabilityMetrics,
        RabbitMetricsRecorder rabbitMetrics
    ) {
        this.reviewMetrics = Objects.requireNonNull(reviewMetrics, "reviewMetrics");
        this.externalMetrics = Objects.requireNonNull(externalMetrics, "externalMetrics");
        this.observabilityMetrics = Objects.requireNonNull(observabilityMetrics, "observabilityMetrics");
        this.rabbitMetrics = Objects.requireNonNull(rabbitMetrics, "rabbitMetrics");
    }

    public static RepoGuardMetrics forTesting(
        MeterRegistry meterRegistry,
        ReviewExecutionFailureClassifier failureClassifier
    ) {
        MetricRecorderSupport metrics = new MetricRecorderSupport(meterRegistry);
        return new RepoGuardMetrics(
            new ReviewMetricsRecorder(metrics, failureClassifier),
            new ExternalMetricsRecorder(metrics),
            new ObservabilityMetricsRecorder(metrics),
            new RabbitMetricsRecorder(metrics)
        );
    }

    public void reviewTaskCreated(String source) {
        reviewMetrics.taskCreated(source);
    }

    public void reviewTaskCompleted(String riskLevel, String llmStatus) {
        reviewMetrics.taskCompleted(riskLevel, llmStatus);
    }

    public void reviewTaskDuration(Duration duration, String result) {
        reviewMetrics.taskDuration(duration, result);
    }

    public void reviewExecutionStageDuration(Duration duration, String stage, String result) {
        reviewMetrics.executionStageDuration(duration, stage, result);
    }

    public void reviewTaskFailed(RuntimeException ex) {
        reviewMetrics.taskFailed(ex);
    }

    public void externalCallFailed(ExternalCallException ex) {
        externalMetrics.callFailed(ex);
    }

    public void externalCallRetried(ExternalCallException ex, int attempt) {
        externalMetrics.callRetried(ex, attempt);
    }

    public void refreshTokenReuseDetected() {
        reviewMetrics.refreshTokenReuseDetected();
    }

    public void githubApiRequest(Duration duration, String operation, String result, String category, String status) {
        externalMetrics.githubApiRequest(duration, operation, result, category, status);
    }

    public void githubDiffDuration(Duration duration, String result) {
        externalMetrics.githubDiffDuration(duration, result);
    }

    public void llmRequestDuration(Duration duration, String result) {
        externalMetrics.llmRequestDuration(duration, result);
    }

    public void llmFallback(String reason) {
        externalMetrics.llmFallback(reason);
    }

    public void githubCommentPublished(String status) {
        externalMetrics.githubCommentPublished(status);
    }

    public void githubCommentPublishDuration(Duration duration, String result) {
        externalMetrics.githubCommentPublishDuration(duration, result);
    }

    public void dataRetentionCleanup(boolean executed, long candidateTasks, int selectedTasks, int deletedTasks) {
        reviewMetrics.dataRetentionCleanup(executed, candidateTasks, selectedTasks, deletedTasks);
    }

    public void dataRetentionCleanupFailed(boolean executed, String reason) {
        reviewMetrics.dataRetentionCleanupFailed(executed, reason);
    }

    public void apiRequest(
        Duration duration,
        String method,
        String path,
        int status,
        String outcome,
        long responseBytes
    ) {
        observabilityMetrics.apiRequest(duration, method, path, status, outcome, responseBytes);
    }

    public void sqlQuery(Duration duration, String statement, String command, String result, long rows) {
        observabilityMetrics.sqlQuery(duration, statement, command, result, rows);
    }

    public void dashboardCacheAccess(String cacheName, String result) {
        observabilityMetrics.dashboardCacheAccess(cacheName, result);
    }

    public void dashboardCacheOperation(String cacheName, String operation) {
        observabilityMetrics.dashboardCacheOperation(cacheName, operation);
    }

    public void frontendApiWaterfallRequest(
        Duration duration,
        String route,
        String operation,
        String path,
        String method,
        String status,
        String result
    ) {
        observabilityMetrics.frontendApiWaterfallRequest(
            duration,
            route,
            operation,
            path,
            method,
            status,
            result
        );
    }

    public void frontendLongTask(Duration duration, String route) {
        observabilityMetrics.frontendLongTask(duration, route);
    }

    public void observabilityThresholdExceeded(String signal, String subject) {
        observabilityMetrics.thresholdExceeded(signal, subject);
    }

    public void rabbitPublishFailed(String reason) {
        rabbitPublishFailed("publish", reason);
    }

    public void rabbitPublishFailed(String failurePhase, String reason) {
        rabbitMetrics.publishFailed(failurePhase, reason);
    }

    public void rabbitMessageConsumed(Duration duration, String result) {
        rabbitMessageConsumed(duration, result, MetricRecorderSupport.UNKNOWN);
    }

    public void rabbitMessageConsumed(Duration duration, String result, String failureCategory) {
        rabbitMetrics.messageConsumed(duration, result, failureCategory);
    }

    public void rabbitQueueDepth(String queue, String state, long depth) {
        rabbitMetrics.queueDepth(queue, state, depth);
    }

    public void rabbitPublishCompensationSucceeded() {
        rabbitPublishCompensationSucceeded("publish");
    }

    public void rabbitPublishCompensationSucceeded(String failurePhase) {
        rabbitMetrics.publishCompensationSucceeded(failurePhase);
    }

    public void rabbitPublishCompensationFailed(String reason) {
        rabbitPublishCompensationFailed("publish", reason);
    }

    public void rabbitPublishCompensationFailed(String failurePhase, String reason) {
        rabbitMetrics.publishCompensationFailed(failurePhase, reason);
    }
}
