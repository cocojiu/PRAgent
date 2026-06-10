package com.repoguard.agent.dto;

public record ActiveRabbitMqConfigDto(
    String provider,
    String status,
    String runtimeConnectionStatus,
    String baseUrl,
    String username,
    String virtualHost,
    String lastCheckedAt,
    String lastError,
    String updatedAt,
    String configVersion,
    String switchNotice
) {
}
