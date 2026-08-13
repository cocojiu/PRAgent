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

class SecretReEncryptionValueProcessorTest {

    private static final String OLD_KEY = "Old-Encryption-Key-2026!Rotate-Primary";
    private static final String NEW_KEY = "New-Encryption-Key-2026!Rotate-Primary";
    private static final String OLD_KEY_ID = "old-2026";
    private static final String NEW_KEY_ID = "new-2026";
    private static final String TEST_SALT = "Re-Encryption-Salt-2026!Primary";

    private final SecretReEncryptionValueProcessor processor = new SecretReEncryptionValueProcessor();
    private final SecretCryptoService sourceCrypto =
        new SecretCryptoService(OLD_KEY, OLD_KEY_ID, TEST_SALT, true);
    private final SecretCryptoService targetCrypto =
        new SecretCryptoService(NEW_KEY, NEW_KEY_ID, TEST_SALT, false);

    @Test
    void reportsEmptyAndTargetKeyValuesAsSkips() {
        var empty = inspect(null, false);
        var target = inspect(targetCrypto.encrypt("already-current"), true);

        assertThat(empty.status()).isEqualTo("SKIPPED_EMPTY");
        assertThat(target.status()).isEqualTo("SKIPPED_TARGET_KEY");
        assertThat(processor.shouldUpdate(empty, true)).isFalse();
        assertThat(processor.shouldUpdate(target, true)).isFalse();
    }

    @Test
    void distinguishesKeyMismatchFromDamagedSourceCiphertext() {
        var mismatch = inspect("enc:v2:legacy-key:not-a-real-payload", false);
        var damaged = inspect("enc:v2:" + OLD_KEY_ID + ":not-a-real-payload", false);

        assertThat(mismatch.status()).isEqualTo("KEY_MISMATCH");
        assertThat(mismatch.failureReason()).isEqualTo("source_key_mismatch");
        assertThat(damaged.status()).isEqualTo("DECRYPT_FAILED");
        assertThat(damaged.failureReason()).isEqualTo("source_decrypt_failed");
        assertThat(processor.isFailure(mismatch.status())).isTrue();
        assertThat(processor.isFailure(damaged.status())).isTrue();
    }

    @Test
    void damagedTargetCiphertextIsNotSilentlySkipped() {
        var damagedTarget = inspect("enc:v3:" + NEW_KEY_ID + ":not-a-real-payload", true);

        assertThat(damagedTarget.status()).isEqualTo("DECRYPT_FAILED");
        assertThat(damagedTarget.failureReason()).isEqualTo("target_decrypt_failed");
        assertThat(processor.shouldUpdate(damagedTarget, true)).isFalse();
    }

    @Test
    void dryRunAndExecuteKeepStatusAndReEncryptionSemanticsAligned() {
        String source = sourceCrypto.encrypt("rotate-me");

        var dryRun = inspect(source, false);
        var execute = inspect(source, true);
        String reEncrypted = processor.reEncrypt(source, sourceCrypto, targetCrypto);

        assertThat(dryRun.status()).isEqualTo("WOULD_RE_ENCRYPT");
        assertThat(execute.status()).isEqualTo("RE_ENCRYPTED");
        assertThat(processor.shouldUpdate(dryRun, false)).isFalse();
        assertThat(processor.shouldUpdate(execute, true)).isTrue();
        assertThat(targetCrypto.decrypt(reEncrypted)).isEqualTo("rotate-me");
    }

    @Test
    void reEncryptsLegacySha256CiphertextToCurrentKdfFormat() throws Exception {
        String legacy = "enc:v2:" + OLD_KEY_ID + ":" + legacySha256Encrypt(OLD_KEY, "legacy-secret");

        var item = inspect(legacy, true);
        String reEncrypted = processor.reEncrypt(legacy, sourceCrypto, targetCrypto);

        assertThat(item.status()).isEqualTo("RE_ENCRYPTED");
        assertThat(reEncrypted).startsWith("enc:v3:" + NEW_KEY_ID + ":");
        assertThat(targetCrypto.decrypt(reEncrypted)).isEqualTo("legacy-secret");
    }

    private com.repoguard.agent.dto.SecretReEncryptionItemDto inspect(String value, boolean execute) {
        return processor.inspect(
            "integration_config",
            1L,
            "token_value",
            "GITHUB",
            value,
            sourceCrypto,
            targetCrypto,
            execute
        );
    }

    private String legacySha256Encrypt(String encryptionKey, String value) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
            Cipher.ENCRYPT_MODE,
            new SecretKeySpec(
                MessageDigest.getInstance("SHA-256").digest(encryptionKey.getBytes(StandardCharsets.UTF_8)),
                "AES"
            ),
            new GCMParameterSpec(128, iv)
        );
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        ByteBuffer payload = ByteBuffer.allocate(iv.length + encrypted.length);
        payload.put(iv);
        payload.put(encrypted);
        return Base64.getEncoder().encodeToString(payload.array());
    }
}
