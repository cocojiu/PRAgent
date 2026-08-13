package com.repoguard.agent.messaging;

public record RabbitPublishSpec(
    String exchange,
    String routingKey,
    long confirmTimeoutMs,
    String correlationIdPrefix
) {
    public static RabbitPublishSpec from(RabbitPublishProperties properties, String correlationIdPrefix) {
        return new RabbitPublishSpec(
            properties.getExchange(),
            properties.getRoutingKey(),
            properties.getPublishConfirmTimeoutMs(),
            correlationIdPrefix
        );
    }

    public long normalizedConfirmTimeoutMs() {
        return Math.max(1, confirmTimeoutMs);
    }
}
