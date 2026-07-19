package com.repoguard.agent.identity;

import java.time.LocalDateTime;

/**
 * Application port for invalidating an account's existing identity sessions.
 */
public interface IdentitySessionInvalidator {

    void invalidateAccountSessions(
        Long userId,
        SessionInvalidationMode mode,
        LocalDateTime occurredAt
    );

    enum SessionInvalidationMode {
        REFRESH_TOKENS_ONLY,
        SESSION_VERSION_ONLY,
        ALL_SESSIONS
    }
}
