package com.repoguard.agent.identity;

import com.repoguard.agent.entity.UserAccount;
import java.time.LocalDateTime;

/**
 * Application port for credential verification and its account-lock/audit side effects.
 */
public interface IdentityCredentialAuthenticator {

    UserAccount authenticate(String account, String password, AuthenticationOperation operation);

    void recordSuccess(
        UserAccount user,
        String account,
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
