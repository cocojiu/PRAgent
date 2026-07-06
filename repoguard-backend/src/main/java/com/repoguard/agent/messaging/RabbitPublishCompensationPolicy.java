package com.repoguard.agent.messaging;

import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
public class RabbitPublishCompensationPolicy {

    private static final int MIN_ATTEMPTS = 1;
    private static final int MIN_BATCH_SIZE = 1;
    private static final long MIN_RETRY_INTERVAL_MS = 1000;
    private static final long MIN_LEASE_MS = 1000;

    public int maxAttempts(int configuredMaxAttempts) {
        return Math.max(MIN_ATTEMPTS, configuredMaxAttempts);
    }

    public int maxAttempts(RabbitPublishCompensationProperties properties) {
        return maxAttempts(properties.getPublishCompensationMaxAttempts());
    }

    public int batchSize(int configuredBatchSize) {
        return Math.max(MIN_BATCH_SIZE, configuredBatchSize);
    }

    public int batchSize(RabbitPublishCompensationProperties properties) {
        return batchSize(properties.getPublishCompensationBatchSize());
    }

    public int nextAttempt(Integer currentAttempts) {
        return (currentAttempts == null ? 0 : currentAttempts) + 1;
    }

    public boolean isTerminalAttempt(int attempt, int configuredMaxAttempts) {
        return attempt >= maxAttempts(configuredMaxAttempts);
    }

    public LocalDateTime nextRetryAt(LocalDateTime from, long configuredRetryIntervalMs) {
        return from.plusNanos(retryIntervalMs(configuredRetryIntervalMs) * 1_000_000);
    }

    public LocalDateTime nextRetryAt(LocalDateTime from, RabbitPublishCompensationProperties properties) {
        return nextRetryAt(from, properties.getPublishCompensationIntervalMs());
    }

    public LocalDateTime expiredBefore(LocalDateTime now, long configuredLeaseMs) {
        return now.minusNanos(leaseMs(configuredLeaseMs) * 1_000_000);
    }

    public long retryIntervalMs(long configuredRetryIntervalMs) {
        return Math.max(MIN_RETRY_INTERVAL_MS, configuredRetryIntervalMs);
    }

    public long leaseMs(long configuredLeaseMs) {
        return Math.max(MIN_LEASE_MS, configuredLeaseMs);
    }
}
