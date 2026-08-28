package com.repoguard.agent.cache;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "repoguard.cache-invalidation")
public class ClusterCacheInvalidationProperties {

    private boolean enabled = true;

    @Min(100)
    private long pollIntervalMs = 1000;

    @Min(1)
    @Max(1000)
    private int batchSize = 200;

    @Min(1)
    @Max(100)
    private int maxBatchesPerPoll = 10;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxBatchesPerPoll() {
        return maxBatchesPerPoll;
    }

    public void setMaxBatchesPerPoll(int maxBatchesPerPoll) {
        this.maxBatchesPerPoll = maxBatchesPerPoll;
    }
}
