package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class SecretCryptoServiceTest {

    @Test
    void encryptAndDecryptRoundTripWithCustomKey() {
        SecretCryptoService service = new SecretCryptoService("test-encryption-key");

        String encrypted = service.encrypt("github-token");

        assertThat(encrypted).startsWith("enc:v1:");
        assertThat(service.decrypt(encrypted)).isEqualTo("github-token");
    }

    @Test
    void nonProductionProfileAllowsDefaultDevelopmentKey() {
        SecretCryptoService service = new SecretCryptoService("repoguard-local-dev-encryption-key", false);

        assertThat(service.decrypt(service.encrypt("local-secret"))).isEqualTo("local-secret");
    }

    @Test
    void productionProfileRejectsDefaultDevelopmentKey() {
        assertThatThrownBy(() -> new SecretCryptoService("repoguard-local-dev-encryption-key", true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Default encryption key is not allowed");
    }

    @Test
    void productionProfileRejectsBlankKey() {
        assertThatThrownBy(() -> new SecretCryptoService(" ", true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("must be configured");
    }

    @Test
    void productionProfileAllowsCustomKey() {
        SecretCryptoService service = new SecretCryptoService("prod-strong-encryption-key", true);

        assertThat(service.decrypt(service.encrypt("prod-secret"))).isEqualTo("prod-secret");
    }
}
