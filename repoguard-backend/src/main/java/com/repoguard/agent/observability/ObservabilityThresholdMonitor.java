package com.repoguard.agent.observability;

import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.stereotype.Component;

@Component
public class ObservabilityThresholdMonitor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ObservabilityThresholdMonitor.class);

    private final RepoGuardMetrics metrics;
    private final ObservabilityThresholdProperties properties;

    public ObservabilityThresholdMonitor(RepoGuardMetrics metrics, ObservabilityThresholdProperties properties) {
        this.metrics = metrics;
        this.properties = properties == null ? new ObservabilityThresholdProperties() : properties;
    }

    public void apiRequest(Duration duration, String path, long responseBytes) {
        if (!properties.isEnabled()) {
            return;
        }
        long durationMs = millis(duration);
        long durationThreshold = thresholdForPath(
            path,
            properties.getApiDurationMsByPath(),
            properties.getApiDurationMs()
        );
        long responseBytesThreshold = thresholdForPath(
            path,
            properties.getApiResponseBytesByPath(),
            properties.getApiResponseBytes()
        );
        if (durationMs >= durationThreshold) {
            thresholdExceeded("api_duration", path, durationMs, durationThreshold, "ms");
        }
        if (responseBytes >= responseBytesThreshold) {
            thresholdExceeded("api_response_bytes", path, responseBytes, responseBytesThreshold, "bytes");
        }
    }

    public void sqlQuery(Duration duration, String statement, long rows) {
        if (!properties.isEnabled()) {
            return;
        }
        long durationMs = millis(duration);
        if (durationMs >= properties.getSqlDurationMs()) {
            thresholdExceeded("sql_duration", statement, durationMs, properties.getSqlDurationMs(), "ms");
        }
        if (rows >= properties.getSqlRows()) {
            thresholdExceeded("sql_rows", statement, rows, properties.getSqlRows(), "rows");
        }
    }

    public void frontendApiRequest(Duration duration, String route) {
        if (!properties.isEnabled()) {
            return;
        }
        long durationMs = millis(duration);
        long threshold = thresholdForSubject(
            route,
            properties.getFrontendApiDurationMsByRoute(),
            properties.getFrontendApiDurationMs()
        );
        if (durationMs >= threshold) {
            thresholdExceeded("frontend_api_duration", route, durationMs, threshold, "ms");
        }
    }

    public void frontendLongTask(Duration duration, String route) {
        if (!properties.isEnabled()) {
            return;
        }
        long durationMs = millis(duration);
        long threshold = thresholdForSubject(
            route,
            properties.getFrontendLongTaskMsByRoute(),
            properties.getFrontendLongTaskMs()
        );
        if (durationMs >= threshold) {
            thresholdExceeded("frontend_long_task", route, durationMs, threshold, "ms");
        }
    }

    private void thresholdExceeded(String signal, String subject, long value, long threshold, String unit) {
        metrics.observabilityThresholdExceeded(signal, subject);
        LOGGER.warn(
            "Observability threshold exceeded signal={} subject={} value={}{} threshold={}{}",
            signal,
            subject,
            value,
            unit,
            threshold,
            unit
        );
    }

    private long millis(Duration duration) {
        if (duration == null || duration.isNegative()) {
            return 0L;
        }
        return duration.toMillis();
    }

    private long thresholdForPath(String path, Map<String, Long> overrides, long defaultValue) {
        return thresholdForSubject(path, overrides, defaultValue);
    }

    private long thresholdForSubject(String subject, Map<String, Long> overrides, long defaultValue) {
        if (!StringUtils.hasText(subject) || overrides == null || overrides.isEmpty()) {
            return defaultValue;
        }
        Long threshold = overrides.get(subject.trim());
        if (threshold == null || threshold <= 0) {
            return defaultValue;
        }
        return threshold;
    }
}
