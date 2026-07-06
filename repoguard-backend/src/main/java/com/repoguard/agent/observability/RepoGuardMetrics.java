package com.repoguard.agent.observability;

import com.repoguard.agent.external.ExternalCallException;
import com.repoguard.agent.worker.ReviewExecutionFailureClassifier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RepoGuardMetrics {

    private static final String UNKNOWN = "unknown";

    private final MeterRegistry meterRegistry;
    private final ReviewExecutionFailureClassifier reviewFailureClassifier = new ReviewExecutionFailureClassifier();
    private final ConcurrentMap<String, AtomicLong> gauges = new ConcurrentHashMap<>();

    public RepoGuardMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public void reviewTaskCreated(String source) {
        counter("repoguard.review.task.created", "source", normalize(source)).increment();
    }

    public void reviewTaskCompleted(String riskLevel, String llmStatus) {
        counter(
            "repoguard.review.task.completed",
            "risk_level", normalize(riskLevel),
            "llm_status", normalize(llmStatus)
        ).increment();
    }

    public void reviewTaskDuration(Duration duration, String result) {
        timer("repoguard.review.task.duration", "result", normalize(result))
            .record(nonNegative(duration));
    }

    public void reviewTaskFailed(RuntimeException ex) {
        counter(
            "repoguard.review.task.failed",
            "category", failureCategory(ex),
            "retryable", retryable(ex)
        ).increment();
    }

    public void externalCallFailed(ExternalCallException ex) {
        counter(
            "repoguard.external.call.failed",
            "system", normalize(ex.getSystem()),
            "category", normalize(ex.getCategory()),
            "retryable", Boolean.toString(ex.isRetryable()),
            "status", ex.getStatusCode() == null ? "none" : ex.getStatusCode().toString()
        ).increment();
    }

    public void githubApiRequest(Duration duration, String operation, String result, String category, String status) {
        timer(
            "repoguard.github.api.request.duration",
            "operation", normalize(operation),
            "result", normalize(result),
            "category", normalize(category),
            "status", normalize(status)
        ).record(nonNegative(duration));
        counter(
            "repoguard.github.api.request",
            "operation", normalize(operation),
            "result", normalize(result),
            "category", normalize(category),
            "status", normalize(status)
        ).increment();
    }

    public void githubDiffDuration(Duration duration, String result) {
        timer("repoguard.github.diff.duration", "result", normalize(result))
            .record(nonNegative(duration));
    }

    public void llmRequestDuration(Duration duration, String result) {
        timer("repoguard.llm.request.duration", "result", normalize(result))
            .record(nonNegative(duration));
    }

    public void llmFallback(String reason) {
        counter("repoguard.llm.fallback", "reason", normalize(reason)).increment();
    }

    public void githubCommentPublished(String status) {
        counter("repoguard.github.comment.publish", "status", normalize(status)).increment();
    }

    public void githubCommentPublishDuration(Duration duration, String result) {
        timer("repoguard.github.comment.publish.duration", "result", normalize(result))
            .record(nonNegative(duration));
    }

    public void apiRequest(
        Duration duration,
        String method,
        String path,
        int status,
        String outcome,
        long responseBytes
    ) {
        String normalizedMethod = normalizeHttpMethod(method);
        String normalizedPath = normalizePath(path);
        String normalizedStatus = normalizeHttpStatus(status);
        String normalizedOutcome = normalize(outcome);
        timer(
            "repoguard.api.request.duration",
            "method", normalizedMethod,
            "path", normalizedPath,
            "status", normalizedStatus,
            "outcome", normalizedOutcome
        ).record(nonNegative(duration));
        summary(
            "repoguard.api.response.bytes",
            "method", normalizedMethod,
            "path", normalizedPath,
            "status", normalizedStatus,
            "outcome", normalizedOutcome
        ).record(Math.max(0L, responseBytes));
    }

    public void sqlQuery(Duration duration, String statement, String command, String result, long rows) {
        String normalizedStatement = normalize(statement);
        String normalizedCommand = normalize(command);
        String normalizedResult = normalize(result);
        timer(
            "repoguard.sql.query.duration",
            "statement", normalizedStatement,
            "command", normalizedCommand,
            "result", normalizedResult
        ).record(nonNegative(duration));
        summaryWithUnit(
            "repoguard.sql.query.rows",
            "rows",
            "statement", normalizedStatement,
            "command", normalizedCommand,
            "result", normalizedResult
        ).record(Math.max(0L, rows));
    }

    public void dashboardCacheAccess(String cacheName, String result) {
        counter(
            "repoguard.dashboard.cache.access",
            "cache", normalize(cacheName),
            "result", normalize(result)
        ).increment();
    }

    public void dashboardCacheOperation(String cacheName, String operation) {
        counter(
            "repoguard.dashboard.cache.operation",
            "cache", normalize(cacheName),
            "operation", normalize(operation)
        ).increment();
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
        String normalizedRoute = normalize(route);
        String normalizedOperation = normalize(operation);
        String normalizedPath = normalizePath(path);
        String normalizedMethod = normalizeHttpMethod(method);
        String normalizedStatus = normalize(status);
        String normalizedResult = normalize(result);
        timer(
            "repoguard.frontend.api.waterfall.duration",
            "route", normalizedRoute,
            "operation", normalizedOperation,
            "path", normalizedPath,
            "method", normalizedMethod,
            "status", normalizedStatus,
            "result", normalizedResult
        ).record(nonNegative(duration));
        counter(
            "repoguard.frontend.api.waterfall.request",
            "route", normalizedRoute,
            "operation", normalizedOperation,
            "path", normalizedPath,
            "method", normalizedMethod,
            "status", normalizedStatus,
            "result", normalizedResult
        ).increment();
    }

    public void frontendLongTask(Duration duration, String route) {
        String normalizedRoute = normalize(route);
        timer(
            "repoguard.frontend.long_task.duration",
            "route", normalizedRoute
        ).record(nonNegative(duration));
        counter(
            "repoguard.frontend.long_task",
            "route", normalizedRoute
        ).increment();
    }

    public void observabilityThresholdExceeded(String signal, String subject) {
        counter(
            "repoguard.observability.threshold.exceeded",
            "signal", normalize(signal),
            "subject", normalize(subject)
        ).increment();
    }

    public void rabbitPublishFailed(String reason) {
        rabbitPublishFailed("publish", reason);
    }

    public void rabbitPublishFailed(String failurePhase, String reason) {
        counter(
            "repoguard.rabbit.publish.failed",
            "failure_phase", normalize(failurePhase),
            "reason", normalize(reason)
        ).increment();
    }

    public void rabbitMessageConsumed(Duration duration, String result) {
        rabbitMessageConsumed(duration, result, UNKNOWN);
    }

    public void rabbitMessageConsumed(Duration duration, String result, String failureCategory) {
        timer(
            "repoguard.rabbit.consume.duration",
            "result", normalize(result),
            "failure_category", normalize(failureCategory)
        )
            .record(nonNegative(duration));
        counter(
            "repoguard.rabbit.consume",
            "result", normalize(result),
            "failure_category", normalize(failureCategory)
        ).increment();
    }

    public void rabbitQueueDepth(String queue, String state, long depth) {
        String normalizedQueue = normalize(queue);
        String normalizedState = normalize(state);
        String key = normalizedQueue + "|" + normalizedState;
        AtomicLong value = gauges.computeIfAbsent(key, ignored -> {
            AtomicLong gaugeValue = new AtomicLong();
            io.micrometer.core.instrument.Gauge.builder("repoguard.rabbit.queue.depth", gaugeValue, AtomicLong::get)
                .tag("queue", normalizedQueue)
                .tag("state", normalizedState)
                .register(meterRegistry);
            return gaugeValue;
        });
        value.set(Math.max(0, depth));
    }

    public void rabbitPublishCompensationSucceeded() {
        rabbitPublishCompensationSucceeded("publish");
    }

    public void rabbitPublishCompensationSucceeded(String failurePhase) {
        counter(
            "repoguard.rabbit.publish.compensation",
            "result", "success",
            "failure_phase", normalize(failurePhase),
            "reason", "none"
        ).increment();
    }

    public void rabbitPublishCompensationFailed(String reason) {
        rabbitPublishCompensationFailed("publish", reason);
    }

    public void rabbitPublishCompensationFailed(String failurePhase, String reason) {
        counter(
            "repoguard.rabbit.publish.compensation",
            "result", "failed",
            "failure_phase", normalize(failurePhase),
            "reason", normalize(reason)
        ).increment();
    }

    private Counter counter(String name, String... tags) {
        return Counter.builder(name)
            .tags(tags)
            .register(meterRegistry);
    }

    private Timer timer(String name, String... tags) {
        return Timer.builder(name)
            .tags(tags)
            .register(meterRegistry);
    }

    private DistributionSummary summary(String name, String... tags) {
        return summaryWithUnit(name, "bytes", tags);
    }

    private DistributionSummary summaryWithUnit(String name, String baseUnit, String... tags) {
        return DistributionSummary.builder(name)
            .baseUnit(baseUnit)
            .tags(tags)
            .register(meterRegistry);
    }

    private Duration nonNegative(Duration duration) {
        if (duration == null || duration.isNegative()) {
            return Duration.ZERO;
        }
        return duration;
    }

    private String failureCategory(RuntimeException ex) {
        return normalize(reviewFailureClassifier.failureCategory(ex));
    }

    private String retryable(RuntimeException ex) {
        if (ex instanceof ExternalCallException externalCallException) {
            return Boolean.toString(externalCallException.isRetryable());
        }
        return UNKNOWN;
    }

    private String normalizeHttpMethod(String value) {
        if (!StringUtils.hasText(value)) {
            return UNKNOWN;
        }
        return value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z]+", "_");
    }

    private String normalizeHttpStatus(int status) {
        return status <= 0 ? UNKNOWN : Integer.toString(status);
    }

    private String normalizePath(String value) {
        if (!StringUtils.hasText(value)) {
            return UNKNOWN;
        }
        String normalized = value.trim().replaceAll("\\s+", "");
        return StringUtils.hasText(normalized) ? normalized.toLowerCase(Locale.ROOT) : UNKNOWN;
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return UNKNOWN;
        }
        return value.trim().toLowerCase().replaceAll("[^a-z0-9_.-]+", "_");
    }
}
