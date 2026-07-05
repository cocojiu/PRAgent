package com.repoguard.agent.dto;

public record ServiceIntegrationConfigDto(
    String provider,
    String status,
    String baseUrl,
    String username,
    String secret,
    String resource,
    String lastCheckedAt,
    String lastError,
    String updatedAt,
    String secretStatus
) {
}
