package com.repoguard.agent.dto;

public record GithubIntegrationConfigDto(
    String provider,
    String status,
    String baseUrl,
    String token,
    String defaultOwner,
    String defaultRepo,
    String lastCheckedAt,
    String lastError,
    String updatedAt
) {
}
