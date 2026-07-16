package com.repoguard.agent.observability;

import java.time.Duration;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class ObservabilityMetricsRecorder {

    private final MetricRecorderSupport metrics;

    public ObservabilityMetricsRecorder(MetricRecorderSupport metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    void apiRequest(Duration duration, String method, String path, int status, String outcome, long responseBytes) {
        String[] tags = {
            "method", metrics.normalizeHttpMethod(method),
            "path", metrics.normalizePath(path),
            "status", metrics.normalizeHttpStatus(status),
            "outcome", metrics.normalize(outcome)
        };
        metrics.timer("repoguard.api.request.duration", tags).record(metrics.nonNegative(duration));
        metrics.summary("repoguard.api.response.bytes", tags).record(Math.max(0L, responseBytes));
    }

    void sqlQuery(Duration duration, String statement, String command, String result, long rows) {
        String[] tags = {
            "statement", metrics.normalize(statement),
            "command", metrics.normalize(command),
            "result", metrics.normalize(result)
        };
        metrics.timer("repoguard.sql.query.duration", tags).record(metrics.nonNegative(duration));
        metrics.summaryWithUnit("repoguard.sql.query.rows", "rows", tags).record(Math.max(0L, rows));
    }

    void dashboardCacheAccess(String cacheName, String result) {
        metrics.counter(
            "repoguard.dashboard.cache.access",
            "cache", metrics.normalize(cacheName),
            "result", metrics.normalize(result)
        ).increment();
    }

    void dashboardCacheOperation(String cacheName, String operation) {
        metrics.counter(
            "repoguard.dashboard.cache.operation",
            "cache", metrics.normalize(cacheName),
            "operation", metrics.normalize(operation)
        ).increment();
    }

    void frontendApiWaterfallRequest(
        Duration duration,
        String route,
        String operation,
        String path,
        String method,
        String status,
        String result
    ) {
        String[] tags = {
            "route", metrics.normalize(route),
            "operation", metrics.normalize(operation),
            "path", metrics.normalizePath(path),
            "method", metrics.normalizeHttpMethod(method),
            "status", metrics.normalize(status),
            "result", metrics.normalize(result)
        };
        metrics.timer("repoguard.frontend.api.waterfall.duration", tags).record(metrics.nonNegative(duration));
        metrics.counter("repoguard.frontend.api.waterfall.request", tags).increment();
    }

    void frontendLongTask(Duration duration, String route) {
        String normalizedRoute = metrics.normalize(route);
        metrics.timer("repoguard.frontend.long_task.duration", "route", normalizedRoute)
            .record(metrics.nonNegative(duration));
        metrics.counter("repoguard.frontend.long_task", "route", normalizedRoute).increment();
    }

    void thresholdExceeded(String signal, String subject) {
        metrics.counter(
            "repoguard.observability.threshold.exceeded",
            "signal", metrics.normalize(signal),
            "subject", metrics.normalize(subject)
        ).increment();
    }
}
