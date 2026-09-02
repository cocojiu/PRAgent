package com.repoguard.agent.scm;

public record ScmIntegrationSettings(
    String provider,
    String status,
    String baseUrl,
    String token,
    String lastError,
    String defaultNamespace,
    String defaultRepository,
    Long id
) {

    public boolean exists() {
        return id != null;
    }

    public boolean configured() {
        return exists() && token != null && !token.isBlank();
    }
}
