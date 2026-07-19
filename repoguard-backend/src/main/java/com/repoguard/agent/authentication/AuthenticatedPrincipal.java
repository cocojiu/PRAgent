package com.repoguard.agent.authentication;

/**
 * Authentication-provider-neutral identity attached to an authenticated request.
 */
public record AuthenticatedPrincipal(
    Long id,
    String username,
    String role,
    long expiresAt,
    int sessionVersion
) {

    public AuthenticatedPrincipal(Long id, String username, String role, long expiresAt) {
        this(id, username, role, expiresAt, 0);
    }
}
