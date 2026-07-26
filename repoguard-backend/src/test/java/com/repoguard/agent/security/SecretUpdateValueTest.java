package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class SecretUpdateValueTest {

    private final SecretCryptoService secretCryptoService = new SecretCryptoService("test-encryption-key");

    @Test
    void maskedSubmittedValueReEncryptsDecryptableExistingSecret() {
        String encrypted = secretCryptoService.encrypt("saved-secret");

        SecretUpdateValue result = SecretUpdateValue.resolve(secretCryptoService, encrypted, "****cret");

        assertThat(result.configured()).isTrue();
        assertThat(result.encryptedValue()).startsWith("enc:v3:local:");
        assertThat(secretCryptoService.decrypt(result.encryptedValue())).isEqualTo("saved-secret");
    }

    @Test
    void maskedSubmittedValueUpgradesLegacySha256CiphertextToActiveFormat() throws Exception {
        String legacy = "enc:v2:local:" + legacySha256Encrypt("test-encryption-key", "saved-secret");

        SecretUpdateValue result = SecretUpdateValue.resolve(secretCryptoService, legacy, "****cret");

        assertThat(result.configured()).isTrue();
        assertThat(result.encryptedValue()).startsWith("enc:v3:local:");
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
    void maskedSubmittedValuePreservesPlaintextExistingSecretWhenPlaintextIsDisabled() {
        SecretUpdateValue result = SecretUpdateValue.resolve(secretCryptoService, "saved-secret", "****");

        assertThat(result.configured()).isTrue();
        assertThat(result.encryptedValue()).isEqualTo("saved-secret");
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

    private String legacySha256Encrypt(String encryptionKey, String value) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
            Cipher.ENCRYPT_MODE,
            new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(encryptionKey.getBytes(StandardCharsets.UTF_8)), "AES"),
            new GCMParameterSpec(128, iv)
        );
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        ByteBuffer payload = ByteBuffer.allocate(iv.length + encrypted.length);
        payload.put(iv);
        payload.put(encrypted);
        return Base64.getEncoder().encodeToString(payload.array());
    }
}
