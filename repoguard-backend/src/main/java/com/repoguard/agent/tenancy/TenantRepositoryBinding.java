package com.repoguard.agent.tenancy;

public record TenantRepositoryBinding(
    Long tenantId,
    String tenantKey,
    String organization,
    String repository,
    Long githubInstallationId
) {
}
