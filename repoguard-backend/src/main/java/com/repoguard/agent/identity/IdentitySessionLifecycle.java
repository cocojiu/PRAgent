package com.repoguard.agent.identity;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Application port for issuing, rotating, revoking, and invalidating identity sessions.
 */
public interface IdentitySessionLifecycle {

    IdentitySessionTokens issue(IdentityAccount account, boolean remember);

    IdentitySessionTokens completeLogin(IdentityAccount account, String presentedAccount, boolean remember);

    IdentitySessionTokens reset(IdentityAccount account, String presentedAccount, boolean remember);

    RefreshResult refresh(String refreshToken);

    void logout(String refreshToken);

    void revokeActiveSessions(Long userId, LocalDateTime occurredAt);

    record RefreshResult(IdentitySessionTokens tokens, String failureMessage) {

        public static RefreshResult success(IdentitySessionTokens tokens) {
            return new RefreshResult(Objects.requireNonNull(tokens, "tokens must not be null"), null);
        }

        public static RefreshResult failure(String failureMessage) {
            return new RefreshResult(
                null,
                Objects.requireNonNull(failureMessage, "failureMessage must not be null")
            );
        }

        public boolean failed() {
            return failureMessage != null;
        }
    }
}
