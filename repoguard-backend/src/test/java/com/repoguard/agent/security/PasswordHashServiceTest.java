package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordHashServiceTest {

    private final PasswordHashService passwordHashService = new PasswordHashService();

    @Test
    void hashUsesBCryptAndDoesNotStoreRawPassword() {
        String hash = passwordHashService.hash("Secure123");

        assertThat(hash).startsWith("$2");
        assertThat(hash).doesNotContain("Secure123");
        assertThat(passwordHashService.matches("Secure123", hash)).isTrue();
        assertThat(passwordHashService.matches("Wrong123", hash)).isFalse();
    }

    @Test
    void productionProfileRejectsDefaultAuthTokenSecret() {
        AuthProperties properties = new AuthProperties();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> properties.validateForProfiles(new String[] {"prod"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("repoguard.auth.token-secret");
    }
}
