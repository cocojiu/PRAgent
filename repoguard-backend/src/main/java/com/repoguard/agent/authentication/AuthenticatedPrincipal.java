package com.repoguard.agent.authentication;

/**
 * Authentication-provider-neutral identity attached to an authenticated request.
 */
public record AuthenticatedPrincipal(
    Long id,
    String username,
    String role,
    long expiresAt,
    int sessionVersion,
    Long tenantId
) {

    public AuthenticatedPrincipal(Long id, String username, String role, long expiresAt, int sessionVersion) {
        this(id, username, role, expiresAt, sessionVersion, null);
    }

    public AuthenticatedPrincipal(Long id, String username, String role, long expiresAt) {
        this(id, username, role, expiresAt, 0, null);
    }

    public AuthenticatedPrincipal withTenant(Long resolvedTenantId, String tenantRole) {
        return new AuthenticatedPrincipal(
            id,
            username,
            tenantRole,
            expiresAt,
            sessionVersion,
            resolvedTenantId
        );
    }
}
