package com.repoguard.agent.identity;

import java.time.LocalDateTime;

/**
 * Application port for identity-owned account registration, profile lookup, and password changes.
 */
public interface IdentityAccountLifecycle {

    IdentitySessionTokens register(RegistrationCommand command);

    Profile currentProfile(Long userId);

    void changePassword(Long userId, PasswordChangeCommand command);

    record RegistrationCommand(
        String username,
        String email,
        String password,
        String confirmPassword
    ) {
    }

    record PasswordChangeCommand(
        String currentPassword,
        String newPassword,
        String confirmPassword
    ) {
    }

    record Profile(
        Long id,
        String username,
        String email,
        String role,
        String status,
        LocalDateTime lastLoginAt
    ) {
    }
}
