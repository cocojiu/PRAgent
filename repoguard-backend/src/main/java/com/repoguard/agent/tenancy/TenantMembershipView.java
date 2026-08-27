package com.repoguard.agent.tenancy;

public record TenantMembershipView(
    Long tenantId,
    String tenantKey,
    String role,
    boolean defaultTenant
) {
}
