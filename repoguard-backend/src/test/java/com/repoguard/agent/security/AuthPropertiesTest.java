package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AuthPropertiesTest {

    @Test
    void productionProfileRejectsDefaultTokenSecret() {
        AuthProperties properties = new AuthProperties();

        assertThatThrownBy(() -> properties.validateForProfiles(new String[] {"prod"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("repoguard.auth.token-secret");
    }

    @Test
    void productionProfileRejectsShortTokenSecret() {
        AuthProperties properties = new AuthProperties();
        properties.setTokenSecret("short-production-secret");

        assertThatThrownBy(() -> properties.validateForProfiles(new String[] {"prod"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("at least 32 characters");
    }

    @Test
    void productionProfileAllowsStrongTokenSecret() {
        AuthProperties properties = new AuthProperties();
        properties.setTokenSecret("0123456789abcdef0123456789abcdef");

        assertThatCode(() -> properties.validateForProfiles(new String[] {"prod"})).doesNotThrowAnyException();
    }

    @Test
    void nonProductionProfilesAllowDevelopmentTokenSecret() {
        AuthProperties properties = new AuthProperties();

        assertThatCode(() -> properties.validateForProfiles(new String[] {"dev", "local"})).doesNotThrowAnyException();
    }
}
