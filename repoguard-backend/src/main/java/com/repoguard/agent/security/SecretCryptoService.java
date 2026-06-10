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

    private static final String PREFIX = "enc:v1:";
    private static final String DEFAULT_ENCRYPTION_KEY = "repoguard-local-dev-encryption-key";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecureRandom secureRandom = new SecureRandom();
    private final SecretKeySpec keySpec;

    @Autowired
    public SecretCryptoService(
        @Value("${repoguard.security.encryption-key:" + DEFAULT_ENCRYPTION_KEY + "}") String encryptionKey,
        Environment environment
    ) {
        this(encryptionKey, environment != null && environment.acceptsProfiles(Profiles.of("prod")));
    }

    public SecretCryptoService(String encryptionKey) {
        this(encryptionKey, false);
    }

    SecretCryptoService(String encryptionKey, boolean productionProfile) {
        validateEncryptionKey(encryptionKey, productionProfile);
        this.keySpec = new SecretKeySpec(sha256(encryptionKey), "AES");
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
            return PREFIX + Base64.getEncoder().encodeToString(payload.array());
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to encrypt secret", ex);
        }
    }

    public String decrypt(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        if (!value.startsWith(PREFIX)) {
            return value;
        }
        try {
            byte[] payload = Base64.getDecoder().decode(value.substring(PREFIX.length()));
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
    }
}
