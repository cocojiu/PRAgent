package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class SecretCryptoServiceTest {

    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    @Test
    void encryptAndDecryptRoundTripWithVersionedCiphertext() {
        SecretCryptoService service = new SecretCryptoService("test-encryption-key");

        String encrypted = service.encrypt("github-token");

        assertThat(encrypted).startsWith("enc:v2:local:");
        assertThat(service.isEncrypted(encrypted)).isTrue();
        assertThat(service.isVersionedCiphertext(encrypted)).isTrue();
        assertThat(service.decrypt(encrypted)).isEqualTo("github-token");
    }

    @Test
    void decryptSupportsLegacyV1Ciphertext() throws Exception {
        SecretCryptoService service = new SecretCryptoService("test-encryption-key");
        String legacyCiphertext = legacyEncrypt("test-encryption-key", "legacy-token");

        assertThat(legacyCiphertext).startsWith("enc:v1:");
        assertThat(service.isEncrypted(legacyCiphertext)).isTrue();
        assertThat(service.isVersionedCiphertext(legacyCiphertext)).isFalse();
        assertThat(service.decrypt(legacyCiphertext)).isEqualTo("legacy-token");
    }

    @Test
    void nonProductionProfileAllowsDefaultDevelopmentKey() {
        SecretCryptoService service = new SecretCryptoService("repoguard-local-dev-encryption-key", false);

        assertThat(service.decrypt(service.encrypt("local-secret"))).isEqualTo("local-secret");
    }

    @Test
    void productionProfileRejectsDefaultDevelopmentKey() {
        assertThatThrownBy(() -> new SecretCryptoService("repoguard-local-dev-encryption-key", "primary", true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Default encryption key is not allowed");
    }

    @Test
    void productionProfileRejectsBlankKey() {
        assertThatThrownBy(() -> new SecretCryptoService(" ", "primary", true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("must be configured");
    }

    @Test
    void productionProfileRejectsWeakKey() {
        assertThatThrownBy(() -> new SecretCryptoService("prod-strong-encryption-key", "primary", true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("at least 32 characters");
    }

    @Test
    void productionProfileRejectsKeyWithoutSymbols() {
        assertThatThrownBy(() -> new SecretCryptoService("ProdEncryptionKey2026RotatePrimary", "primary", true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("letters, digits, and symbols");
    }

    @Test
    void productionProfileRejectsDefaultKeyId() {
        assertThatThrownBy(() -> new SecretCryptoService("Prod-Encryption-Key-2026!Rotate-Primary", "local", true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Default encryption key id is not allowed");
    }

    @Test
    void productionProfileAllowsStrongCustomKeyAndKeyId() {
        SecretCryptoService service = new SecretCryptoService("Prod-Encryption-Key-2026!Rotate-Primary", "primary-2026-06", true);

        assertThat(service.decrypt(service.encrypt("prod-secret"))).isEqualTo("prod-secret");
    }

    @Test
    void versionedCiphertextRejectsMismatchedKeyId() {
        SecretCryptoService firstKey = new SecretCryptoService("test-encryption-key", "key-a", false);
        SecretCryptoService secondKey = new SecretCryptoService("test-encryption-key", "key-b", false);

        String encrypted = firstKey.encrypt("secret");

        assertThatThrownBy(() -> secondKey.decrypt(encrypted))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("key id does not match");
    }

    private String legacyEncrypt(String encryptionKey, String value) throws Exception {
        byte[] iv = new byte[IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
            Cipher.ENCRYPT_MODE,
            new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(encryptionKey.getBytes(StandardCharsets.UTF_8)), "AES"),
            new GCMParameterSpec(TAG_LENGTH_BITS, iv)
        );
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        ByteBuffer payload = ByteBuffer.allocate(iv.length + encrypted.length);
        payload.put(iv);
        payload.put(encrypted);
        return "enc:v1:" + Base64.getEncoder().encodeToString(payload.array());
    }
}
