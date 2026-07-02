package com.repoguard.agent.messaging;

public record RabbitPublishSpec(
    String exchange,
    String routingKey,
    int maxAttempts,
    long initialBackoffMs,
    double backoffMultiplier,
    long confirmTimeoutMs,
    String correlationIdPrefix
) {
    public int normalizedMaxAttempts() {
        return Math.max(1, maxAttempts);
    }

    public long normalizedInitialBackoffMs() {
        return Math.max(0, initialBackoffMs);
    }

    public double normalizedBackoffMultiplier() {
        return backoffMultiplier <= 0 ? 1.0 : backoffMultiplier;
    }

    public long normalizedConfirmTimeoutMs() {
        return Math.max(1, confirmTimeoutMs);
    }
}
