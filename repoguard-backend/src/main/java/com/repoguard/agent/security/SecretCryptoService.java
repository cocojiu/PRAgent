package com.repoguard.agent.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SecretCryptoService {

    private static final String V1_PREFIX = "enc:v1:";
    private static final String V2_PREFIX = "enc:v2:";
    private static final String DEFAULT_ENCRYPTION_KEY = "repoguard-local-dev-encryption-key";
    private static final String DEFAULT_KEY_ID = "local";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int PROD_MIN_KEY_LENGTH = 32;

    private final SecureRandom secureRandom = new SecureRandom();
    private final SecretKeySpec keySpec;
    private final String activeKeyId;

    @Autowired
    public SecretCryptoService(
        @Value("${repoguard.security.encryption-key:" + DEFAULT_ENCRYPTION_KEY + "}") String encryptionKey,
        @Value("${repoguard.security.encryption-key-id:" + DEFAULT_KEY_ID + "}") String activeKeyId,
        Environment environment
    ) {
        this(encryptionKey, activeKeyId, environment != null && environment.acceptsProfiles(Profiles.of("prod")));
    }

    public SecretCryptoService(String encryptionKey) {
        this(encryptionKey, DEFAULT_KEY_ID, false);
    }

    SecretCryptoService(String encryptionKey, boolean productionProfile) {
        this(encryptionKey, DEFAULT_KEY_ID, productionProfile);
    }

    SecretCryptoService(String encryptionKey, String activeKeyId, boolean productionProfile) {
        validateEncryptionKey(encryptionKey, productionProfile);
        validateKeyId(activeKeyId, productionProfile);
        this.keySpec = new SecretKeySpec(sha256(encryptionKey), "AES");
        this.activeKeyId = activeKeyId.trim();
    }

    public String encrypt(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            ByteBuffer payload = ByteBuffer.allocate(iv.length + encrypted.length);
            payload.put(iv);
            payload.put(encrypted);
            return V2_PREFIX + activeKeyId + ":" + Base64.getEncoder().encodeToString(payload.array());
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to encrypt secret", ex);
        }
    }

    public String decrypt(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        if (value.startsWith(V1_PREFIX)) {
            return decryptPayload(value.substring(V1_PREFIX.length()));
        }
        if (value.startsWith(V2_PREFIX)) {
            int payloadStart = value.indexOf(':', V2_PREFIX.length());
            if (payloadStart <= V2_PREFIX.length()) {
                throw new IllegalStateException("Invalid encrypted secret format");
            }
            String keyId = value.substring(V2_PREFIX.length(), payloadStart);
            if (!activeKeyId.equals(keyId)) {
                throw new IllegalStateException("Encrypted secret key id does not match active encryption key");
            }
            return decryptPayload(value.substring(payloadStart + 1));
        }
        if (!value.startsWith("enc:")) {
            return value;
        }
        throw new IllegalStateException("Unsupported encrypted secret format");
    }

    public boolean isEncrypted(String value) {
        return StringUtils.hasText(value) && value.startsWith("enc:");
    }

    public boolean isVersionedCiphertext(String value) {
        return StringUtils.hasText(value) && value.startsWith(V2_PREFIX);
    }

    private String decryptPayload(String encodedPayload) {
        try {
            byte[] payload = Base64.getDecoder().decode(encodedPayload);
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            byte[] iv = new byte[IV_LENGTH];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to decrypt secret", ex);
        }
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to create encryption key", ex);
        }
    }

    private void validateEncryptionKey(String encryptionKey, boolean productionProfile) {
        if (!productionProfile) {
            return;
        }
        if (!StringUtils.hasText(encryptionKey)) {
            throw new IllegalStateException("repoguard.security.encryption-key must be configured in prod profile");
        }
        if (DEFAULT_ENCRYPTION_KEY.equals(encryptionKey.trim())) {
            throw new IllegalStateException("Default encryption key is not allowed in prod profile");
        }
        if (encryptionKey.trim().length() < PROD_MIN_KEY_LENGTH) {
            throw new IllegalStateException("repoguard.security.encryption-key must be at least 32 characters in prod profile");
        }
        if (!hasEnterpriseComplexity(encryptionKey.trim())) {
            throw new IllegalStateException("repoguard.security.encryption-key must include letters, digits, and symbols in prod profile");
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
            throw new IllegalStateException("Default encryption key id is not allowed in prod profile");
        }
    }

    private boolean hasEnterpriseComplexity(String value) {
        boolean hasLetter = value.chars().anyMatch(Character::isLetter);
        boolean hasDigit = value.chars().anyMatch(Character::isDigit);
        boolean hasSymbol = value.chars().anyMatch(ch -> !Character.isLetterOrDigit(ch));
        return hasLetter && hasDigit && hasSymbol;
    }
}
