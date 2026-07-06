package com.repoguard.agent.observability;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
        if (durationMs >= properties.getApiDurationMs()) {
            thresholdExceeded("api_duration", path, durationMs, properties.getApiDurationMs(), "ms");
        }
        if (responseBytes >= properties.getApiResponseBytes()) {
            thresholdExceeded("api_response_bytes", path, responseBytes, properties.getApiResponseBytes(), "bytes");
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
        if (durationMs >= properties.getFrontendApiDurationMs()) {
            thresholdExceeded("frontend_api_duration", route, durationMs, properties.getFrontendApiDurationMs(), "ms");
        }
    }

    public void frontendLongTask(Duration duration, String route) {
        if (!properties.isEnabled()) {
            return;
        }
        long durationMs = millis(duration);
        if (durationMs >= properties.getFrontendLongTaskMs()) {
            thresholdExceeded("frontend_long_task", route, durationMs, properties.getFrontendLongTaskMs(), "ms");
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
}
