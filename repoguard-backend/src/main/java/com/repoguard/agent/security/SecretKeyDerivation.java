package com.repoguard.agent.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

final class SecretKeyDerivation {

    static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
    static final int PBKDF2_ITERATIONS = 310_000;
    static final int DERIVED_KEY_LENGTH_BITS = 256;

    private static final int MAX_CACHED_KEYS = 32;
    private static final Map<String, SecretKey> DERIVED_KEYS = new ConcurrentHashMap<>();

    private SecretKeyDerivation() {
    }

    static SecretKey legacyDigest(String encryptionKey) {
        try {
            return new SecretKeySpec(
                MessageDigest.getInstance("SHA-256").digest(encryptionKey.getBytes(StandardCharsets.UTF_8)),
                "AES"
            );
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to create encryption key", ex);
        }
    }

    static SecretKey derive(String encryptionKey, byte[] salt) {
        String cacheKey = cacheKey(encryptionKey, salt);
        SecretKey cached = DERIVED_KEYS.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        SecretKey derived = pbkdf2(encryptionKey, salt);
        if (DERIVED_KEYS.size() < MAX_CACHED_KEYS) {
            DERIVED_KEYS.putIfAbsent(cacheKey, derived);
        }
        return derived;
    }

    private static SecretKey pbkdf2(String encryptionKey, byte[] salt) {
        PBEKeySpec keySpec = new PBEKeySpec(
            encryptionKey.toCharArray(),
            salt,
            PBKDF2_ITERATIONS,
            DERIVED_KEY_LENGTH_BITS
        );
        try {
            return new SecretKeySpec(
                SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(keySpec).getEncoded(),
                "AES"
            );
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to derive encryption key", ex);
        } finally {
            keySpec.clearPassword();
        }
    }

    private static String cacheKey(String encryptionKey, byte[] salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            digest.update((byte) 0);
            digest.update(encryptionKey.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest.digest());
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to derive encryption key", ex);
        }
    }
}
