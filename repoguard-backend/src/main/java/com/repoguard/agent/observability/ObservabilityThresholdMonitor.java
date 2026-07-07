package com.repoguard.agent.observability;

import com.repoguard.agent.external.ExternalCallException;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public void apiRequest(Duration duration, String path, long responseBytes) {
        apiRequest(duration, null, path, responseBytes);
    }

    public void apiRequest(Duration duration, String method, String path, long responseBytes) {
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
        String subject = apiSubject(method, path);
        if (durationMs >= durationThreshold) {
            thresholdExceeded("api_duration", subject, durationMs, durationThreshold, "ms");
        }
        if (responseBytes >= responseBytesThreshold) {
            thresholdExceeded("api_response_bytes", subject, responseBytes, responseBytesThreshold, "bytes");
        }
    }

    public void sqlQuery(Duration duration, String statement, long rows) {
        sqlQuery(duration, statement, null, null, rows);
    }

    public void sqlQuery(Duration duration, String statement, String command, String result, long rows) {
        if (!properties.isEnabled()) {
            return;
        }
        long durationMs = millis(duration);
        String subject = sqlSubject(statement, command, result);
        if (durationMs >= properties.getSqlDurationMs()) {
            thresholdExceeded("sql_duration", subject, durationMs, properties.getSqlDurationMs(), "ms");
        }
        if (rows >= properties.getSqlRows()) {
            thresholdExceeded("sql_rows", subject, rows, properties.getSqlRows(), "rows");
        }
    }

    public void frontendApiRequest(Duration duration, String route) {
        frontendApiRequest(duration, route, null, null);
    }

    public void frontendApiRequest(Duration duration, String route, String operation, String path) {
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
            thresholdExceeded(
                "frontend_api_duration",
                frontendApiSubject(route, operation, path),
                durationMs,
                threshold,
                "ms"
            );
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

    public void externalCallRetry(ExternalCallException ex, int attempt) {
        if (!properties.isEnabled()) {
            return;
        }
        Objects.requireNonNull(ex, "ex");
        long normalizedAttempt = Math.max(1, attempt);
        long threshold = thresholdForSubject(
            externalCallSystem(ex),
            properties.getExternalCallRetryAttemptBySystem(),
            properties.getExternalCallRetryAttempt()
        );
        if (normalizedAttempt >= threshold) {
            thresholdExceeded(
                "external_call_retry_attempt",
                externalCallSubject(ex),
                normalizedAttempt,
                threshold,
                "attempts"
            );
        }
    }

    public void dataRetentionCleanupFailure(boolean executed, String reason) {
        if (!properties.isEnabled()) {
            return;
        }
        long threshold = Math.max(1, properties.getDataRetentionCleanupFailures());
        if (threshold <= 1) {
            thresholdExceeded(
                "data_retention_cleanup_failed",
                dataRetentionCleanupSubject(executed, reason),
                1,
                threshold,
                "failures"
            );
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

    private String frontendApiSubject(String route, String operation, String path) {
        StringBuilder subject = new StringBuilder(safeSubject(route));
        if (StringUtils.hasText(operation)) {
            subject.append('|').append(operation.trim());
        }
        if (StringUtils.hasText(path)) {
            subject.append('|').append(path.trim());
        }
        return subject.toString();
    }

    private String apiSubject(String method, String path) {
        if (!StringUtils.hasText(method)) {
            return safeSubject(path);
        }
        return method.trim() + "|" + safeSubject(path);
    }

    private String sqlSubject(String statement, String command, String result) {
        StringBuilder subject = new StringBuilder(safeSubject(statement));
        if (StringUtils.hasText(command)) {
            subject.append('|').append(command.trim());
        }
        if (StringUtils.hasText(result)) {
            subject.append('|').append(result.trim());
        }
        return subject.toString();
    }

    private String externalCallSubject(ExternalCallException ex) {
        StringBuilder subject = new StringBuilder(externalCallSystem(ex));
        subject.append('|').append(safeSubject(ex.getCategory()));
        subject.append('|').append(ex.getStatusCode() == null ? "none" : ex.getStatusCode());
        return subject.toString();
    }

    private String externalCallSystem(ExternalCallException ex) {
        return safeSubject(ex.getSystem()).toLowerCase(Locale.ROOT);
    }

    private String dataRetentionCleanupSubject(boolean executed, String reason) {
        return (executed ? "execute" : "dry_run") + "|" + safeSubject(reason);
    }

    private String safeSubject(String subject) {
        return StringUtils.hasText(subject) ? subject.trim() : "unknown";
    }
}
