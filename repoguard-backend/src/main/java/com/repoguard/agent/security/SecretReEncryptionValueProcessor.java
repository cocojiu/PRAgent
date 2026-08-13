package com.repoguard.agent.security;

import com.repoguard.agent.dto.SecretReEncryptionItemDto;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class SecretReEncryptionValueProcessor {

    static final String STATUS_RE_ENCRYPTED = "RE_ENCRYPTED";
    static final String STATUS_WOULD_RE_ENCRYPT = "WOULD_RE_ENCRYPT";
    static final String STATUS_SKIPPED_EMPTY = "SKIPPED_EMPTY";
    static final String STATUS_SKIPPED_TARGET_KEY = "SKIPPED_TARGET_KEY";
    static final String STATUS_KEY_MISMATCH = "KEY_MISMATCH";
    static final String STATUS_DECRYPT_FAILED = "DECRYPT_FAILED";
    static final String FAILURE_REASON_KEY_MISMATCH = "source_key_mismatch";
    static final String FAILURE_REASON_DECRYPT_FAILED = "source_decrypt_failed";
    static final String FAILURE_REASON_TARGET_DECRYPT_FAILED = "target_decrypt_failed";

    public SecretReEncryptionItemDto inspect(
        String tableName,
        Long recordId,
        String fieldName,
        String provider,
        String value,
        SecretCryptoService sourceCrypto,
        SecretCryptoService targetCrypto,
        boolean execute
    ) {
        if (!StringUtils.hasText(value)) {
            return item(
                tableName,
                recordId,
                fieldName,
                provider,
                "empty",
                null,
                targetCrypto,
                STATUS_SKIPPED_EMPTY,
                null,
                "No secret value configured"
            );
        }

        String sourceKeyId = null;
        String sourceFormat = sourceCrypto.ciphertextFormat(value);
        try {
            sourceKeyId = sourceCrypto.encryptedKeyId(value);
            if (targetCrypto.isActiveCiphertext(value)) {
                try {
                    targetCrypto.decrypt(value);
                    return item(
                        tableName,
                        recordId,
                        fieldName,
                        provider,
                        sourceFormat,
                        sourceKeyId,
                        targetCrypto,
                        STATUS_SKIPPED_TARGET_KEY,
                        null,
                        "Already encrypted with target key id"
                    );
                } catch (Exception ex) {
                    return item(
                        tableName,
                        recordId,
                        fieldName,
                        provider,
                        sourceFormat,
                        sourceKeyId,
                        targetCrypto,
                        STATUS_DECRYPT_FAILED,
                        FAILURE_REASON_TARGET_DECRYPT_FAILED,
                        "Secret is labeled with the target key id but cannot be decrypted with the target key"
                    );
                }
            }
            if (sourceCrypto.isVersionedCiphertext(value) && !sourceCrypto.activeKeyId().equals(sourceKeyId)) {
                return item(
                    tableName,
                    recordId,
                    fieldName,
                    provider,
                    sourceFormat,
                    sourceKeyId,
                    targetCrypto,
                    STATUS_KEY_MISMATCH,
                    FAILURE_REASON_KEY_MISMATCH,
                    "Encrypted secret key id does not match source encryption key id"
                );
            }
            sourceCrypto.decrypt(value);
            return item(
                tableName,
                recordId,
                fieldName,
                provider,
                sourceFormat,
                sourceKeyId,
                targetCrypto,
                execute ? STATUS_RE_ENCRYPTED : STATUS_WOULD_RE_ENCRYPT,
                null,
                execute ? "Secret was re-encrypted" : "Secret can be re-encrypted"
            );
        } catch (Exception ex) {
            return item(
                tableName,
                recordId,
                fieldName,
                provider,
                sourceFormat,
                sourceKeyId,
                targetCrypto,
                STATUS_DECRYPT_FAILED,
                FAILURE_REASON_DECRYPT_FAILED,
                "Secret cannot be decrypted with source encryption key"
            );
        }
    }

    public String reEncrypt(
        String value,
        SecretCryptoService sourceCrypto,
        SecretCryptoService targetCrypto
    ) {
        return targetCrypto.encrypt(sourceCrypto.decrypt(value));
    }

    public boolean shouldUpdate(SecretReEncryptionItemDto item, boolean execute) {
        return execute && STATUS_RE_ENCRYPTED.equals(item.status());
    }

    public boolean isFailure(String status) {
        return STATUS_KEY_MISMATCH.equals(status) || STATUS_DECRYPT_FAILED.equals(status);
    }

    private SecretReEncryptionItemDto item(
        String tableName,
        Long recordId,
        String fieldName,
        String provider,
        String sourceFormat,
        String sourceKeyId,
        SecretCryptoService targetCrypto,
        String status,
        String failureReason,
        String message
    ) {
        return new SecretReEncryptionItemDto(
            tableName,
            recordId,
            fieldName,
            provider,
            sourceFormat,
            sourceKeyId,
            targetCrypto.activeKeyId(),
            status,
            failureReason,
            message
        );
    }
}
