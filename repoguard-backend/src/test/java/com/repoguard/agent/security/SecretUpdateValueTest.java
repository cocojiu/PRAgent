package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecretUpdateValueTest {

    private final SecretCryptoService secretCryptoService = new SecretCryptoService("test-encryption-key");

    @Test
    void maskedSubmittedValueReEncryptsDecryptableExistingSecret() {
        String encrypted = secretCryptoService.encrypt("saved-secret");

        SecretUpdateValue result = SecretUpdateValue.resolve(secretCryptoService, encrypted, "****cret");

        assertThat(result.configured()).isTrue();
        assertThat(result.encryptedValue()).startsWith("enc:v2:local:");
        assertThat(secretCryptoService.decrypt(result.encryptedValue())).isEqualTo("saved-secret");
    }

    @Test
    void maskedSubmittedValuePreservesDamagedExistingSecret() {
        String damaged = "enc:v2:local:not-a-real-payload";

        SecretUpdateValue result = SecretUpdateValue.resolve(secretCryptoService, damaged, "****");

        assertThat(result.configured()).isTrue();
        assertThat(result.encryptedValue()).isEqualTo(damaged);
    }

    @Test
    void blankSubmittedValueClearsSecret() {
        SecretUpdateValue result = SecretUpdateValue.resolve(secretCryptoService, "saved-secret", " ");

        assertThat(result.configured()).isFalse();
        assertThat(result.encryptedValue()).isNull();
    }

    @Test
    void plainSubmittedValueEncryptsNewSecret() {
        SecretUpdateValue result = SecretUpdateValue.resolve(secretCryptoService, "saved-secret", " new-secret ");

        assertThat(result.configured()).isTrue();
        assertThat(secretCryptoService.decrypt(result.encryptedValue())).isEqualTo("new-secret");
    }
}
