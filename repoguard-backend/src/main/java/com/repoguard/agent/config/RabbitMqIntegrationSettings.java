package com.repoguard.agent.config;

import java.time.LocalDateTime;

public record RabbitMqIntegrationSettings(
    String provider,
    String status,
    String baseUrl,
    String username,
    String virtualHost,
    LocalDateTime lastCheckedAt,
    String lastError,
    LocalDateTime updatedAt
) {

    private static final String RABBITMQ_PROVIDER = "RABBITMQ";

    public static RabbitMqIntegrationSettings empty() {
        return new RabbitMqIntegrationSettings(RABBITMQ_PROVIDER, "NOT_CONFIGURED", null, null, null, null, null, null);
    }
}
