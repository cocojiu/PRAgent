package com.repoguard.agent.security;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "repoguard.security.re-encryption")
public class SecretReEncryptionProperties {

    @Min(1)
    @Max(500)
    private int batchSize = 100;

    @Min(10)
    @Max(3600)
    private int leaseSeconds = 120;

    @Min(1)
    @Max(3600)
    private int retryDelaySeconds = 30;

    @Min(1)
    @Max(20)
    private int maxAttempts = 5;

    @Min(100)
    @Max(60000)
    private long pollIntervalMs = 1000;

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(int leaseSeconds) {
        this.leaseSeconds = leaseSeconds;
    }

    public int getRetryDelaySeconds() {
        return retryDelaySeconds;
    }

    public void setRetryDelaySeconds(int retryDelaySeconds) {
        this.retryDelaySeconds = retryDelaySeconds;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getPollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(long pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }
}
