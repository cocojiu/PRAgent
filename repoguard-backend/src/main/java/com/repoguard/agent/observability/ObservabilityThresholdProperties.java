package com.repoguard.agent.observability;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "repoguard.observability.thresholds")
public class ObservabilityThresholdProperties {

    private boolean enabled = true;
    private long apiDurationMs = 2000;
    private long apiResponseBytes = 524288;
    private long sqlDurationMs = 500;
    private long sqlRows = 1000;
    private long frontendApiDurationMs = 2000;
    private long frontendLongTaskMs = 200;
    private long externalCallRetryAttempt = 1;
    private long dataRetentionCleanupFailures = 1;
    private Map<String, Long> apiDurationMsByPath = new HashMap<>();
    private Map<String, Long> apiResponseBytesByPath = new HashMap<>();
    private Map<String, Long> frontendApiDurationMsByRoute = new HashMap<>();
    private Map<String, Long> frontendLongTaskMsByRoute = new HashMap<>();
    private Map<String, Long> externalCallRetryAttemptBySystem = new HashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getApiDurationMs() {
        return apiDurationMs;
    }

    public void setApiDurationMs(long apiDurationMs) {
        this.apiDurationMs = apiDurationMs;
    }

    public long getApiResponseBytes() {
        return apiResponseBytes;
    }

    public void setApiResponseBytes(long apiResponseBytes) {
        this.apiResponseBytes = apiResponseBytes;
    }

    public long getSqlDurationMs() {
        return sqlDurationMs;
    }

    public void setSqlDurationMs(long sqlDurationMs) {
        this.sqlDurationMs = sqlDurationMs;
    }

    public long getSqlRows() {
        return sqlRows;
    }

    public void setSqlRows(long sqlRows) {
        this.sqlRows = sqlRows;
    }

    public long getFrontendApiDurationMs() {
        return frontendApiDurationMs;
    }

    public void setFrontendApiDurationMs(long frontendApiDurationMs) {
        this.frontendApiDurationMs = frontendApiDurationMs;
    }

    public long getFrontendLongTaskMs() {
        return frontendLongTaskMs;
    }

    public void setFrontendLongTaskMs(long frontendLongTaskMs) {
        this.frontendLongTaskMs = frontendLongTaskMs;
    }

    public long getExternalCallRetryAttempt() {
        return externalCallRetryAttempt;
    }

    public void setExternalCallRetryAttempt(long externalCallRetryAttempt) {
        this.externalCallRetryAttempt = externalCallRetryAttempt;
    }

    public long getDataRetentionCleanupFailures() {
        return dataRetentionCleanupFailures;
    }

    public void setDataRetentionCleanupFailures(long dataRetentionCleanupFailures) {
        this.dataRetentionCleanupFailures = dataRetentionCleanupFailures;
    }

    public Map<String, Long> getApiDurationMsByPath() {
        return apiDurationMsByPath;
    }

    public void setApiDurationMsByPath(Map<String, Long> apiDurationMsByPath) {
        this.apiDurationMsByPath = apiDurationMsByPath == null ? new HashMap<>() : new HashMap<>(apiDurationMsByPath);
    }

    public Map<String, Long> getApiResponseBytesByPath() {
        return apiResponseBytesByPath;
    }

    public void setApiResponseBytesByPath(Map<String, Long> apiResponseBytesByPath) {
        this.apiResponseBytesByPath = apiResponseBytesByPath == null
            ? new HashMap<>()
            : new HashMap<>(apiResponseBytesByPath);
    }

    public Map<String, Long> getFrontendApiDurationMsByRoute() {
        return frontendApiDurationMsByRoute;
    }

    public void setFrontendApiDurationMsByRoute(Map<String, Long> frontendApiDurationMsByRoute) {
        this.frontendApiDurationMsByRoute = frontendApiDurationMsByRoute == null
            ? new HashMap<>()
            : new HashMap<>(frontendApiDurationMsByRoute);
    }

    public Map<String, Long> getFrontendLongTaskMsByRoute() {
        return frontendLongTaskMsByRoute;
    }

    public void setFrontendLongTaskMsByRoute(Map<String, Long> frontendLongTaskMsByRoute) {
        this.frontendLongTaskMsByRoute = frontendLongTaskMsByRoute == null
            ? new HashMap<>()
            : new HashMap<>(frontendLongTaskMsByRoute);
    }

    public Map<String, Long> getExternalCallRetryAttemptBySystem() {
        return externalCallRetryAttemptBySystem;
    }

    public void setExternalCallRetryAttemptBySystem(Map<String, Long> externalCallRetryAttemptBySystem) {
        this.externalCallRetryAttemptBySystem = externalCallRetryAttemptBySystem == null
            ? new HashMap<>()
            : new HashMap<>(externalCallRetryAttemptBySystem);
    }
}
