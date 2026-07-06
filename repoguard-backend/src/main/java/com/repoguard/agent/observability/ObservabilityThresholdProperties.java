package com.repoguard.agent.observability;

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
}
