package com.repoguard.agent.config;

public record GithubIntegrationSettings(
    String provider,
    String status,
    String baseUrl,
    String token,
    String lastError,
    String defaultOwner,
    String defaultRepo,
    Long id
) {

    private static final String GITHUB_PROVIDER = "GITHUB";

    public static GithubIntegrationSettings empty() {
        return new GithubIntegrationSettings(GITHUB_PROVIDER, null, null, null, null, null, null, null);
    }
}
