package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SecretCryptoServiceTest {

    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final String DEFAULT_SALT = "repoguard-local-dev-encryption-salt";

    @Test
    void encryptProducesActiveV3CiphertextAndDecryptRoundTrips() {
        SecretCryptoService service = new SecretCryptoService("test-encryption-key");

        String encrypted = service.encrypt("github-token");

        assertThat(encrypted).startsWith("enc:v3:local:");
        assertThat(service.isEncrypted(encrypted)).isTrue();
        assertThat(service.isVersionedCiphertext(encrypted)).isTrue();
        assertThat(service.isActiveCiphertext(encrypted)).isTrue();
        assertThat(service.ciphertextFormat(encrypted)).isEqualTo("enc:v3");
        assertThat(service.decrypt(encrypted)).isEqualTo("github-token");
    }

    @Test
    void encryptDerivesKeyWithPbkdf2AndBindsKeyIdAsAad() throws Exception {
        SecretCryptoService service = new SecretCryptoService("test-encryption-key");

        String encrypted = service.encrypt("github-token");

        String payload = encrypted.substring("enc:v3:local:".length());
        SecretKey derivedKey = pbkdf2Key("test-encryption-key", DEFAULT_SALT + ":local");
        assertThat(gcmDecrypt(derivedKey, "local".getBytes(StandardCharsets.UTF_8), payload)).isEqualTo("github-token");
    }

    @Test
    void decryptSupportsLegacySha256V1Ciphertext() throws Exception {
        SecretCryptoService service = new SecretCryptoService("test-encryption-key");
        String legacyCiphertext = "enc:v1:" + gcmEncrypt(sha256Key("test-encryption-key"), null, "legacy-token");

        assertThat(service.isEncrypted(legacyCiphertext)).isTrue();
        assertThat(service.isVersionedCiphertext(legacyCiphertext)).isFalse();
        assertThat(service.decrypt(legacyCiphertext)).isEqualTo("legacy-token");
    }

    @Test
    void decryptSupportsLegacySha256V2CiphertextWithoutAad() throws Exception {
        SecretCryptoService service = new SecretCryptoService("test-encryption-key");
        String legacyCiphertext = "enc:v2:local:" + gcmEncrypt(sha256Key("test-encryption-key"), null, "legacy-v2-token");

        assertThat(service.isVersionedCiphertext(legacyCiphertext)).isTrue();
        assertThat(service.isActiveCiphertext(legacyCiphertext)).isFalse();
        assertThat(service.ciphertextFormat(legacyCiphertext)).isEqualTo("enc:v2");
        assertThat(service.decrypt(legacyCiphertext)).isEqualTo("legacy-v2-token");
    }

    @Test
    void decryptRejectsV3CiphertextWithoutMatchingAad() throws Exception {
        SecretCryptoService service = new SecretCryptoService("test-encryption-key");
        SecretKey derivedKey = pbkdf2Key("test-encryption-key", DEFAULT_SALT + ":local");
        String forged = "enc:v3:local:" + gcmEncrypt(derivedKey, null, "github-token");

        assertThatThrownBy(() -> service.decrypt(forged))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Unable to decrypt secret");
    }

    @Test
    void decryptRejectsV3CiphertextTransplantedToAnotherKeyId() {
        SecretCryptoService firstKey = new SecretCryptoService("test-encryption-key", "key-a", false);
        SecretCryptoService secondKey = new SecretCryptoService("test-encryption-key", "key-b", false);

        String transplanted = "enc:v3:key-b:" + firstKey.encrypt("secret").substring("enc:v3:key-a:".length());

        assertThatThrownBy(() -> secondKey.decrypt(transplanted))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Unable to decrypt secret");
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

    @Test
    void decryptRejectsPlaintextValueByDefaultAndRecordsMetric() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SecretCryptoService service = new SecretCryptoService("test-encryption-key", "local", null, false, meterRegistry, false);

        assertThatThrownBy(() -> service.decrypt("raw-github-token"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("plaintext secrets are disabled");
        assertThat(meterRegistry.counter("repoguard.security.plaintext_secret_rejected", "key_id", "local").count())
            .isEqualTo(1.0d);
    }

    @Test
    void decryptAllowsPlaintextValueOnlyWhenMigrationFlagIsEnabled() {
        SecretCryptoService service = new SecretCryptoService("test-encryption-key", "local", null, true, null, false);

        assertThat(service.decrypt("raw-github-token")).isEqualTo("raw-github-token");
    }

    @Test
    void decryptRejectsUnknownEncryptedFormatEvenWhenPlaintextIsAllowed() {
        SecretCryptoService service = new SecretCryptoService("test-encryption-key", "local", null, true, null, false);

        assertThatThrownBy(() -> service.decrypt("enc:v9:local:payload"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Unsupported encrypted secret format");
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
    void stagingProfileRejectsDefaultDevelopmentKey() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("staging");

        assertThatThrownBy(() -> new SecretCryptoService(
            "repoguard-local-dev-encryption-key",
            "primary",
            "Staging-Encryption-Salt-2026!Rotate",
            false,
            null,
            environment
        ))
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
    void productionProfileRejectsMissingSalt() {
        assertThatThrownBy(() -> new SecretCryptoService("Prod-Encryption-Key-2026!Rotate-Primary", "primary", true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("repoguard.security.encryption-salt must be configured");
    }

    @Test
    void productionProfileRejectsDefaultSalt() {
        assertThatThrownBy(() -> new SecretCryptoService("Prod-Encryption-Key-2026!Rotate-Primary", "primary", DEFAULT_SALT, true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Default encryption salt is not allowed");
    }

    @Test
    void productionProfileRejectsShortSalt() {
        assertThatThrownBy(() -> new SecretCryptoService("Prod-Encryption-Key-2026!Rotate-Primary", "primary", "short-salt", true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("at least 16 characters");
    }

    @Test
    void productionProfileAllowsStrongCustomKeyKeyIdAndSalt() {
        SecretCryptoService service = new SecretCryptoService(
            "Prod-Encryption-Key-2026!Rotate-Primary",
            "primary-2026-06",
            "Prod-Encryption-Salt-2026!Rotate",
            true
        );

        assertThat(service.decrypt(service.encrypt("prod-secret"))).isEqualTo("prod-secret");
    }

    private SecretKey sha256Key(String encryptionKey) throws Exception {
        return new SecretKeySpec(
            MessageDigest.getInstance("SHA-256").digest(encryptionKey.getBytes(StandardCharsets.UTF_8)),
            "AES"
        );
    }

    private SecretKey pbkdf2Key(String encryptionKey, String salt) throws Exception {
        PBEKeySpec keySpec = new PBEKeySpec(
            encryptionKey.toCharArray(),
            salt.getBytes(StandardCharsets.UTF_8),
            310_000,
            256
        );
        return new SecretKeySpec(
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(keySpec).getEncoded(),
            "AES"
        );
    }

    private String gcmEncrypt(SecretKey key, byte[] additionalData, String value) throws Exception {
        byte[] iv = new byte[IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        if (additionalData != null) {
            cipher.updateAAD(additionalData);
        }
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        ByteBuffer payload = ByteBuffer.allocate(iv.length + encrypted.length);
        payload.put(iv);
        payload.put(encrypted);
        return Base64.getEncoder().encodeToString(payload.array());
    }

    private String gcmDecrypt(SecretKey key, byte[] additionalData, String encodedPayload) throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(encodedPayload));
        byte[] iv = new byte[IV_LENGTH];
        buffer.get(iv);
        byte[] encrypted = new byte[buffer.remaining()];
        buffer.get(encrypted);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
        if (additionalData != null) {
            cipher.updateAAD(additionalData);
        }
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }
}
