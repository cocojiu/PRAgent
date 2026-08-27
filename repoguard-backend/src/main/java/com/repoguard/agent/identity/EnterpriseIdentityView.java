package com.repoguard.agent.identity;

public record EnterpriseIdentityView(
    Long tenantId,
    Long userId,
    String username,
    String role,
    String status,
    Integer sessionVersion
) {
}
