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
    void stagingProfileAlsoRejectsDefaultTokenSecret() {
        AuthProperties properties = new AuthProperties();

        assertThatThrownBy(() -> properties.validateForProfiles(new String[] {"staging"}))
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
        properties.setSecureCookies(true);

        assertThatCode(() -> properties.validateForProfiles(new String[] {"prod"})).doesNotThrowAnyException();
    }

    @Test
    void productionProfileRejectsInsecureCookies() {
        AuthProperties properties = new AuthProperties();
        properties.setTokenSecret("0123456789abcdef0123456789abcdef");

        assertThatThrownBy(() -> properties.validateForProfiles(new String[] {"prod"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("repoguard.auth.secure-cookies");
    }

    @Test
    void localAndTestProfilesAllowDevelopmentTokenSecret() {
        AuthProperties properties = new AuthProperties();

        assertThatCode(() -> properties.validateForProfiles(new String[] {"dev", "local"})).doesNotThrowAnyException();
        assertThatCode(() -> properties.validateForProfiles(new String[] {"test"})).doesNotThrowAnyException();
    }

    @Test
    void rejectsTokenSecretIdWithUnsupportedCharacters() {
        AuthProperties properties = new AuthProperties();
        properties.setTokenSecretId("k1:evil");

        assertThatThrownBy(() -> properties.validateForProfiles(new String[] {"dev"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("repoguard.auth.token-secret-id");
    }

    @Test
    void rejectsBlankTokenSecretId() {
        AuthProperties properties = new AuthProperties();
        properties.setTokenSecretId("  ");

        assertThatThrownBy(() -> properties.validateForProfiles(new String[] {"dev"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("repoguard.auth.token-secret-id");
    }

    @Test
    void rejectsPreviousTokenSecretWithoutKeyId() {
        AuthProperties properties = new AuthProperties();
        properties.setTokenSecretPrevious("previous-secret");

        assertThatThrownBy(() -> properties.validateForProfiles(new String[] {"dev"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("must be configured together");
    }

    @Test
    void rejectsPreviousKeyIdWithoutPreviousTokenSecret() {
        AuthProperties properties = new AuthProperties();
        properties.setTokenSecretPreviousId("k0");

        assertThatThrownBy(() -> properties.validateForProfiles(new String[] {"dev"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("must be configured together");
    }

    @Test
    void rejectsPreviousKeyIdCollidingWithActiveKeyId() {
        AuthProperties properties = new AuthProperties();
        properties.setTokenSecretId("k1");
        properties.setTokenSecretPrevious("previous-secret");
        properties.setTokenSecretPreviousId("k1");

        assertThatThrownBy(() -> properties.validateForProfiles(new String[] {"dev"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("must differ from repoguard.auth.token-secret-id");
    }

    @Test
    void rejectsPreviousTokenSecretIdenticalToActiveTokenSecret() {
        AuthProperties properties = new AuthProperties();
        properties.setTokenSecret("same-secret");
        properties.setTokenSecretPrevious("same-secret");
        properties.setTokenSecretPreviousId("k0");

        assertThatThrownBy(() -> properties.validateForProfiles(new String[] {"dev"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("must differ from repoguard.auth.token-secret");
    }

    @Test
    void allowsRotationPairWithDistinctKeyIds() {
        AuthProperties properties = new AuthProperties();
        properties.setTokenSecret("0123456789abcdef0123456789abcdef");
        properties.setTokenSecretId("k2");
        properties.setTokenSecretPrevious("fedcba9876543210fedcba9876543210");
        properties.setTokenSecretPreviousId("k1");
        properties.setSecureCookies(true);

        assertThatCode(() -> properties.validateForProfiles(new String[] {"prod"})).doesNotThrowAnyException();
    }

    @Test
    void productionProfileRejectsShortPreviousTokenSecret() {
        AuthProperties properties = new AuthProperties();
        properties.setTokenSecret("0123456789abcdef0123456789abcdef");
        properties.setTokenSecretId("k2");
        properties.setTokenSecretPrevious("short-previous-secret");
        properties.setTokenSecretPreviousId("k1");
        properties.setSecureCookies(true);

        assertThatThrownBy(() -> properties.validateForProfiles(new String[] {"prod"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("repoguard.auth.token-secret-previous must be at least 32 characters");
    }

    @Test
    void rejectsNonPositiveAccessTokenTtl() {
        AuthProperties properties = new AuthProperties();
        properties.setAccessTokenTtlSeconds(0);

        assertThatThrownBy(() -> properties.validateForProfiles(new String[] {"dev"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("access-token-ttl-seconds");
    }

    @Test
    void rejectsNonPositiveRefreshTokenTtl() {
        AuthProperties properties = new AuthProperties();
        properties.setRefreshTokenTtlSeconds(-1);

        assertThatThrownBy(() -> properties.validateForProfiles(new String[] {"dev"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("refresh-token-ttl-seconds");
    }

    @Test
    void rejectsNonPositiveRememberTokenTtl() {
        AuthProperties properties = new AuthProperties();
        properties.setRememberTokenTtlSeconds(0);

        assertThatThrownBy(() -> properties.validateForProfiles(new String[] {"dev"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("remember-token-ttl-seconds");
    }

    @Test
    void rejectsNegativeRefreshConcurrencyGrace() {
        AuthProperties properties = new AuthProperties();
        properties.setRefreshConcurrencyGraceSeconds(-1);

        assertThatThrownBy(() -> properties.validateForProfiles(new String[] {"dev"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("refresh-concurrency-grace-seconds");
    }
}
