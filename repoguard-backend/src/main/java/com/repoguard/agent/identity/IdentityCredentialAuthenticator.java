package com.repoguard.agent.identity;

import java.time.LocalDateTime;

/**
 * Application port for credential verification and its account-lock/audit side effects.
 */
public interface IdentityCredentialAuthenticator {

    IdentityAccount authenticate(String account, String password, AuthenticationOperation operation);

    void recordSuccess(
        IdentityAccount account,
        String presentedAccount,
        AuthenticationOperation operation,
        LocalDateTime occurredAt
    );

    enum AuthenticationOperation {
        LOGIN("LOGIN", true),
        TOKEN_RESET("TOKEN_RESET", false);

        private final String auditEventType;
        private final boolean clearsLoginFailures;

        AuthenticationOperation(String auditEventType, boolean clearsLoginFailures) {
            this.auditEventType = auditEventType;
            this.clearsLoginFailures = clearsLoginFailures;
        }

        public String auditEventType() {
            return auditEventType;
        }

        public boolean clearsLoginFailures() {
            return clearsLoginFailures;
        }
    }
}
