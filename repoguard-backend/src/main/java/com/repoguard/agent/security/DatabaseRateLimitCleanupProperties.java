package com.repoguard.agent.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.security.rate-limit-cleanup")
public class DatabaseRateLimitCleanupProperties {
    private boolean enabled = true;
    private int batchSize = 500;
    private int maxBatchesPerRun = 10;
    private int maxRunMs = 3_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int value) { batchSize = value; }
    public int getMaxBatchesPerRun() { return maxBatchesPerRun; }
    public void setMaxBatchesPerRun(int value) { maxBatchesPerRun = value; }
    public int getMaxRunMs() { return maxRunMs; }
    public void setMaxRunMs(int value) { maxRunMs = value; }
    public int normalizedBatchSize() { return Math.max(1, Math.min(batchSize, 5_000)); }
    public int normalizedMaxBatchesPerRun() { return Math.max(1, Math.min(maxBatchesPerRun, 20)); }
    public int normalizedMaxRunMs() { return Math.max(2_000, Math.min(maxRunMs, 30_000)); }
}
