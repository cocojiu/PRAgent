package com.repoguard.agent.security;

import org.springframework.util.StringUtils;

public record SecretUpdateValue(String encryptedValue, boolean configured) {

    public static SecretUpdateValue resolve(
        SecretCryptoService cryptoService,
        String encryptedCurrentValue,
        String submittedValue
    ) {
        if (submittedValue == null || submittedValue.trim().startsWith("****")) {
            return keepExisting(cryptoService, encryptedCurrentValue);
        }

        String trimmed = submittedValue.trim();
        if (!StringUtils.hasText(trimmed)) {
            return new SecretUpdateValue(null, false);
        }
        return new SecretUpdateValue(cryptoService.encrypt(trimmed), true);
    }

    private static SecretUpdateValue keepExisting(SecretCryptoService cryptoService, String encryptedCurrentValue) {
        if (!StringUtils.hasText(encryptedCurrentValue)) {
            return new SecretUpdateValue(null, false);
        }
        SecretValueView secret = SecretValueView.inspect(cryptoService, encryptedCurrentValue);
        if (!SecretValueView.STATUS_CONFIGURED.equals(secret.status())) {
            return new SecretUpdateValue(encryptedCurrentValue, true);
        }
        return new SecretUpdateValue(cryptoService.encrypt(cryptoService.decrypt(encryptedCurrentValue)), true);
    }
}
