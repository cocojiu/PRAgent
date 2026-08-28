package com.repoguard.agent.messaging;

public record RabbitPublishSpec(
    String exchange,
    String routingKey,
    long confirmTimeoutMs,
    String correlationIdPrefix,
    Integer priority
) {
    public RabbitPublishSpec(String exchange, String routingKey, long confirmTimeoutMs, String correlationIdPrefix) {
        this(exchange, routingKey, confirmTimeoutMs, correlationIdPrefix, null);
    }

    public static RabbitPublishSpec from(RabbitPublishProperties properties, String correlationIdPrefix) {
        return new RabbitPublishSpec(
            properties.getExchange(),
            properties.getRoutingKey(),
            properties.getPublishConfirmTimeoutMs(),
            correlationIdPrefix,
            null
        );
    }

    public long normalizedConfirmTimeoutMs() {
        return Math.max(1, confirmTimeoutMs);
    }

    public RabbitPublishSpec withPriority(Integer messagePriority) {
        return new RabbitPublishSpec(exchange, routingKey, confirmTimeoutMs, correlationIdPrefix, messagePriority);
    }
}
