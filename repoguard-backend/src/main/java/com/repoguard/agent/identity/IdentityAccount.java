package com.repoguard.agent.identity;

/**
 * Persistence-neutral account snapshot shared by identity application ports.
 */
public record IdentityAccount(
    Long id,
    String username,
    String email,
    String role,
    int sessionVersion
) {

    public IdentityAccount withSessionVersion(int newSessionVersion) {
        return new IdentityAccount(id, username, email, role, newSessionVersion);
    }
}
