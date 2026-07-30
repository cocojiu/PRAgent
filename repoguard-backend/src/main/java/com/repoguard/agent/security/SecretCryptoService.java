package com.repoguard.agent.security;

import com.repoguard.agent.config.RuntimeProfilePolicy;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SecretCryptoService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SecretCryptoService.class);
    private static final String ENCRYPTED_PREFIX = "enc:";
    private static final String V1_PREFIX = "enc:v1:";
    private static final String V2_PREFIX = "enc:v2:";
    private static final String V3_PREFIX = "enc:v3:";
    private static final String DEFAULT_ENCRYPTION_KEY = "repoguard-local-dev-encryption-key";
    private static final String DEFAULT_ENCRYPTION_SALT = "repoguard-local-dev-encryption-salt";
    private static final String DEFAULT_KEY_ID = "local";
    private static final String FORMAT_PLAINTEXT = "plaintext";
    private static final String FORMAT_UNKNOWN = "unknown";
    private static final String FORMAT_EMPTY = "empty";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int PROD_MIN_KEY_LENGTH = 32;
    private static final int PROD_MIN_SALT_LENGTH = 16;

    private final SecureRandom secureRandom = new SecureRandom();
    private final SecretKey legacyKey;
    private final SecretKey derivedKey;
    private final String encryptionSalt;
    private final String activeKeyId;
    private final byte[] activeKeyIdAad;
    private final boolean allowPlaintextSecrets;
    private final MeterRegistry meterRegistry;

    @Autowired
    public SecretCryptoService(
        @Value("${repoguard.security.encryption-key}") String encryptionKey,
        @Value("${repoguard.security.encryption-key-id}") String activeKeyId,
        @Value("${repoguard.security.encryption-salt:}") String encryptionSalt,
        @Value("${repoguard.security.allow-plaintext-secrets:false}") boolean allowPlaintextSecrets,
        MeterRegistry meterRegistry,
        Environment environment
    ) {
        this(
            encryptionKey,
            activeKeyId,
            encryptionSalt,
            allowPlaintextSecrets,
            meterRegistry,
            environment != null && RuntimeProfilePolicy.isProductionLike(environment.getActiveProfiles())
        );
    }

    public SecretCryptoService(String encryptionKey) {
        this(encryptionKey, DEFAULT_KEY_ID, null, false);
    }

    SecretCryptoService(String encryptionKey, boolean productionProfile) {
        this(encryptionKey, DEFAULT_KEY_ID, null, productionProfile);
    }

    SecretCryptoService(String encryptionKey, String activeKeyId, boolean productionProfile) {
        this(encryptionKey, activeKeyId, null, productionProfile);
    }

    SecretCryptoService(String encryptionKey, String activeKeyId, String encryptionSalt, boolean productionProfile) {
        this(encryptionKey, activeKeyId, encryptionSalt, false, null, productionProfile);
    }

    SecretCryptoService(
        String encryptionKey,
        String activeKeyId,
        String encryptionSalt,
        boolean allowPlaintextSecrets,
        MeterRegistry meterRegistry,
        boolean productionProfile
    ) {
        validateEncryptionKey(encryptionKey, productionProfile);
        validateKeyId(activeKeyId, productionProfile);
        validateEncryptionSalt(encryptionSalt, productionProfile);
        this.activeKeyId = activeKeyId.trim();
        this.encryptionSalt = StringUtils.hasText(encryptionSalt) ? encryptionSalt.trim() : DEFAULT_ENCRYPTION_SALT;
        this.activeKeyIdAad = this.activeKeyId.getBytes(StandardCharsets.UTF_8);
        this.legacyKey = SecretKeyDerivation.legacyDigest(encryptionKey);
        this.derivedKey = SecretKeyDerivation.derive(
            encryptionKey,
            (this.encryptionSalt + ":" + this.activeKeyId).getBytes(StandardCharsets.UTF_8)
        );
        this.allowPlaintextSecrets = allowPlaintextSecrets;
        this.meterRegistry = meterRegistry;
    }

    SecretCryptoService migrationSource(String encryptionKey, String keyId) {
        return new SecretCryptoService(encryptionKey, keyId, encryptionSalt, true, meterRegistry, false);
    }

    SecretCryptoService migrationTarget(String encryptionKey, String keyId) {
        return new SecretCryptoService(encryptionKey, keyId, encryptionSalt, false, meterRegistry, true);
    }

    public String encrypt(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, derivedKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(activeKeyIdAad);
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            ByteBuffer payload = ByteBuffer.allocate(iv.length + encrypted.length);
            payload.put(iv);
            payload.put(encrypted);
            return V3_PREFIX + activeKeyId + ":" + Base64.getEncoder().encodeToString(payload.array());
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to encrypt secret", ex);
        }
    }

    public String decrypt(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        if (value.startsWith(V1_PREFIX)) {
            return decryptPayload(value.substring(V1_PREFIX.length()), legacyKey, null);
        }
        if (value.startsWith(V2_PREFIX)) {
            return decryptPayload(versionedPayload(value, V2_PREFIX), legacyKey, null);
        }
        if (value.startsWith(V3_PREFIX)) {
            return decryptPayload(versionedPayload(value, V3_PREFIX), derivedKey, activeKeyIdAad);
        }
        if (!value.startsWith(ENCRYPTED_PREFIX)) {
            return plaintextOrReject(value);
        }
        throw new IllegalStateException("Unsupported encrypted secret format");
    }

    public boolean isEncrypted(String value) {
        return StringUtils.hasText(value) && value.startsWith(ENCRYPTED_PREFIX);
    }

    public boolean isVersionedCiphertext(String value) {
        return StringUtils.hasText(value) && versionedPrefix(value) != null;
    }

    public boolean isActiveCiphertext(String value) {
        if (!StringUtils.hasText(value) || !value.startsWith(V3_PREFIX)) {
            return false;
        }
        int payloadStart = value.indexOf(':', V3_PREFIX.length());
        return payloadStart > V3_PREFIX.length()
            && activeKeyId.equals(value.substring(V3_PREFIX.length(), payloadStart));
    }

    public String ciphertextFormat(String value) {
        if (!StringUtils.hasText(value)) {
            return FORMAT_EMPTY;
        }
        if (value.startsWith(V1_PREFIX)) {
            return "enc:v1";
        }
        if (value.startsWith(V2_PREFIX)) {
            return "enc:v2";
        }
        if (value.startsWith(V3_PREFIX)) {
            return "enc:v3";
        }
        return value.startsWith(ENCRYPTED_PREFIX) ? FORMAT_UNKNOWN : FORMAT_PLAINTEXT;
    }

    public String encryptedKeyId(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        if (value.startsWith(V1_PREFIX)) {
            return "v1";
        }
        String prefix = versionedPrefix(value);
        if (prefix == null) {
            return null;
        }
        int payloadStart = value.indexOf(':', prefix.length());
        if (payloadStart <= prefix.length()) {
            throw new IllegalStateException("Invalid encrypted secret format");
        }
        return value.substring(prefix.length(), payloadStart);
    }

    public String activeKeyId() {
        return activeKeyId;
    }

    private String versionedPrefix(String value) {
        if (value.startsWith(V2_PREFIX)) {
            return V2_PREFIX;
        }
        return value.startsWith(V3_PREFIX) ? V3_PREFIX : null;
    }

    private String versionedPayload(String value, String prefix) {
        int payloadStart = value.indexOf(':', prefix.length());
        if (payloadStart <= prefix.length()) {
            throw new IllegalStateException("Invalid encrypted secret format");
        }
        String keyId = value.substring(prefix.length(), payloadStart);
        if (!activeKeyId.equals(keyId)) {
            throw new IllegalStateException("Encrypted secret key id does not match active encryption key");
        }
        return value.substring(payloadStart + 1);
    }

    private String plaintextOrReject(String value) {
        if (allowPlaintextSecrets) {
            return value;
        }
        if (meterRegistry != null) {
            meterRegistry.counter("repoguard.security.plaintext_secret_rejected", "key_id", activeKeyId).increment();
        }
        LOGGER.warn(
            "Rejected a stored secret that is not encrypted; re-encrypt it with key id {} "
                + "or temporarily set repoguard.security.allow-plaintext-secrets=true for the migration window",
            activeKeyId
        );
        throw new IllegalStateException("Stored secret is not encrypted and plaintext secrets are disabled");
    }

    private String decryptPayload(String encodedPayload, SecretKey key, byte[] additionalData) {
        try {
            byte[] payload = Base64.getDecoder().decode(encodedPayload);
            ByteBuffer buffer = ByteBuffer.wrap(payload);
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
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to decrypt secret", ex);
        }
    }

    private void validateEncryptionKey(String encryptionKey, boolean productionProfile) {
        if (!productionProfile) {
            return;
        }
        if (!StringUtils.hasText(encryptionKey)) {
            throw new IllegalStateException("repoguard.security.encryption-key must be configured in a production-like profile");
        }
        if (DEFAULT_ENCRYPTION_KEY.equals(encryptionKey.trim())) {
            throw new IllegalStateException("Default encryption key is not allowed in a production-like profile");
        }
        if (encryptionKey.trim().length() < PROD_MIN_KEY_LENGTH) {
            throw new IllegalStateException("repoguard.security.encryption-key must be at least 32 characters in a production-like profile");
        }
        if (!hasEnterpriseComplexity(encryptionKey.trim())) {
            throw new IllegalStateException("repoguard.security.encryption-key must include letters, digits, and symbols in a production-like profile");
        }
    }

    private void validateKeyId(String keyId, boolean productionProfile) {
        if (!StringUtils.hasText(keyId)) {
            throw new IllegalStateException("repoguard.security.encryption-key-id must be configured");
        }
        String normalizedKeyId = keyId.trim();
        if (!normalizedKeyId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalStateException("repoguard.security.encryption-key-id contains unsupported characters");
        }
        if (productionProfile && DEFAULT_KEY_ID.equals(normalizedKeyId)) {
            throw new IllegalStateException("Default encryption key id is not allowed in a production-like profile");
        }
    }

    private void validateEncryptionSalt(String encryptionSalt, boolean productionProfile) {
        if (!productionProfile) {
            return;
        }
        if (!StringUtils.hasText(encryptionSalt)) {
            throw new IllegalStateException("repoguard.security.encryption-salt must be configured in a production-like profile");
        }
        if (DEFAULT_ENCRYPTION_SALT.equals(encryptionSalt.trim())) {
            throw new IllegalStateException("Default encryption salt is not allowed in a production-like profile");
        }
        if (encryptionSalt.trim().length() < PROD_MIN_SALT_LENGTH) {
            throw new IllegalStateException("repoguard.security.encryption-salt must be at least 16 characters in a production-like profile");
        }
    }

    private boolean hasEnterpriseComplexity(String value) {
        boolean hasLetter = value.chars().anyMatch(Character::isLetter);
        boolean hasDigit = value.chars().anyMatch(Character::isDigit);
        boolean hasSymbol = value.chars().anyMatch(ch -> !Character.isLetterOrDigit(ch));
        return hasLetter && hasDigit && hasSymbol;
    }
}
