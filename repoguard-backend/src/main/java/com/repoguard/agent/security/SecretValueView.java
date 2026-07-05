package com.repoguard.agent.security;

import org.springframework.util.StringUtils;

public record SecretValueView(String maskedValue, String status) {

    public static final String STATUS_MISSING = "missing";
    public static final String STATUS_CONFIGURED = "configured";
    public static final String STATUS_KEY_MISMATCH = "key_mismatch";
    public static final String STATUS_DECRYPT_FAILED = "decrypt_failed";

    public static SecretValueView inspect(SecretCryptoService cryptoService, String value) {
        if (!StringUtils.hasText(value)) {
            return new SecretValueView(null, STATUS_MISSING);
        }
        if (cryptoService.isVersionedCiphertext(value)) {
            String keyId = encryptedKeyId(cryptoService, value);
            if (keyId == null) {
                return new SecretValueView(null, STATUS_DECRYPT_FAILED);
            }
            if (!cryptoService.activeKeyId().equals(keyId)) {
                return new SecretValueView(null, STATUS_KEY_MISMATCH);
            }
        }
        try {
            return new SecretValueView(mask(cryptoService.decrypt(value)), STATUS_CONFIGURED);
        } catch (RuntimeException ex) {
            return new SecretValueView(null, STATUS_DECRYPT_FAILED);
        }
    }

    private static String encryptedKeyId(SecretCryptoService cryptoService, String value) {
        try {
            return cryptoService.encryptedKeyId(value);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String mask(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        int visible = Math.min(4, trimmed.length());
        return "****" + trimmed.substring(trimmed.length() - visible);
    }
}
