package com.repoguard.agent.security;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.SecretReEncryptionItemDto;
import com.repoguard.agent.dto.SecretReEncryptionRequest;
import com.repoguard.agent.dto.SecretReEncryptionResponse;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.mapper.NotificationChannelBindingMapper;
import com.repoguard.agent.mapper.ReviewPolicyConfigMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class SecretReEncryptionService {

    private static final String CONFIRM_TEXT = "RE-ENCRYPT";
    private static final String DEFAULT_SOURCE_KEY_ID = "local";
    private static final String STATUS_RE_ENCRYPTED = "RE_ENCRYPTED";
    private static final String STATUS_WOULD_RE_ENCRYPT = "WOULD_RE_ENCRYPT";
    private static final String STATUS_SKIPPED_EMPTY = "SKIPPED_EMPTY";
    private static final String STATUS_SKIPPED_TARGET_KEY = "SKIPPED_TARGET_KEY";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_KEY_MISMATCH = "KEY_MISMATCH";
    private static final String STATUS_DECRYPT_FAILED = "DECRYPT_FAILED";
    private static final String FAILURE_REASON_KEY_MISMATCH = "source_key_mismatch";
    private static final String FAILURE_REASON_DECRYPT_FAILED = "source_decrypt_failed";

    private final IntegrationConfigMapper integrationConfigMapper;
    private final ReviewPolicyConfigMapper reviewPolicyConfigMapper;
    private final NotificationChannelBindingMapper notificationChannelBindingMapper;
    private final SecretCryptoService secretCryptoService;

    public SecretReEncryptionService(
        IntegrationConfigMapper integrationConfigMapper,
        ReviewPolicyConfigMapper reviewPolicyConfigMapper,
        NotificationChannelBindingMapper notificationChannelBindingMapper,
        SecretCryptoService secretCryptoService
    ) {
        this.integrationConfigMapper = integrationConfigMapper;
        this.reviewPolicyConfigMapper = reviewPolicyConfigMapper;
        this.notificationChannelBindingMapper = notificationChannelBindingMapper;
        this.secretCryptoService = secretCryptoService;
    }

    @Transactional
    public SecretReEncryptionResponse reEncrypt(SecretReEncryptionRequest request) {
        boolean execute = Boolean.TRUE.equals(request.execute());
        if (execute && !CONFIRM_TEXT.equals(request.confirmText())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "confirmText must be RE-ENCRYPT when execute is true");
        }

        String sourceKeyId = StringUtils.hasText(request.sourceKeyId())
            ? request.sourceKeyId().trim()
            : DEFAULT_SOURCE_KEY_ID;
        SecretCryptoService sourceCrypto = secretCryptoService.migrationSource(request.sourceEncryptionKey(), sourceKeyId);
        SecretCryptoService targetCrypto = secretCryptoService.migrationTarget(
            request.targetEncryptionKey(),
            request.targetKeyId()
        );
        List<SecretReEncryptionItemDto> items = new ArrayList<>();

        for (IntegrationConfig config : integrationConfigMapper.selectList(null)) {
            items.add(processIntegration(config, sourceCrypto, targetCrypto, execute));
        }
        for (ReviewPolicyConfig config : reviewPolicyConfigMapper.selectList(null)) {
            items.add(processReviewPolicy(config, sourceCrypto, targetCrypto, execute));
        }
        for (NotificationChannelBinding binding : notificationChannelBindingMapper.selectList(null)) {
            items.addAll(processNotificationBinding(binding, sourceCrypto, targetCrypto, execute));
        }

        int reEncryptedCount = (int) items.stream()
            .filter(item -> STATUS_RE_ENCRYPTED.equals(item.status()) || STATUS_WOULD_RE_ENCRYPT.equals(item.status()))
            .count();
        int failedCount = (int) items.stream().filter(item -> isFailedStatus(item.status())).count();
        int skippedCount = items.size() - reEncryptedCount - failedCount;
        return new SecretReEncryptionResponse(
            execute,
            items.size(),
            reEncryptedCount,
            skippedCount,
            failedCount,
            items
        );
    }

    private SecretReEncryptionItemDto processIntegration(
        IntegrationConfig config,
        SecretCryptoService sourceCrypto,
        SecretCryptoService targetCrypto,
        boolean execute
    ) {
        SecretReEncryptionItemDto item = processValue(
            "integration_config",
            config.getId(),
            "token_value",
            config.getProvider(),
            config.getTokenValue(),
            sourceCrypto,
            targetCrypto,
            execute
        );
        if (shouldUpdate(item, execute)) {
            config.setTokenValue(targetCrypto.encrypt(sourceCrypto.decrypt(config.getTokenValue())));
            config.setUpdatedAt(LocalDateTime.now());
            integrationConfigMapper.updateById(config);
        }
        return item;
    }

    private SecretReEncryptionItemDto processReviewPolicy(
        ReviewPolicyConfig config,
        SecretCryptoService sourceCrypto,
        SecretCryptoService targetCrypto,
        boolean execute
    ) {
        SecretReEncryptionItemDto item = processValue(
            "review_policy_config",
            config.getId(),
            "api_key_value",
            config.getLlmProvider(),
            config.getApiKeyValue(),
            sourceCrypto,
            targetCrypto,
            execute
        );
        if (shouldUpdate(item, execute)) {
            config.setApiKeyValue(targetCrypto.encrypt(sourceCrypto.decrypt(config.getApiKeyValue())));
            config.setUpdatedAt(LocalDateTime.now());
            reviewPolicyConfigMapper.updateById(config);
        }
        return item;
    }

    private List<SecretReEncryptionItemDto> processNotificationBinding(
        NotificationChannelBinding binding,
        SecretCryptoService sourceCrypto,
        SecretCryptoService targetCrypto,
        boolean execute
    ) {
        List<SecretReEncryptionItemDto> items = new ArrayList<>();
        SecretReEncryptionItemDto webhookUrl = processValue(
            "notification_channel_binding",
            binding.getId(),
            "webhook_url_value",
            binding.getProvider(),
            binding.getWebhookUrlValue(),
            sourceCrypto,
            targetCrypto,
            execute
        );
        SecretReEncryptionItemDto secret = processValue(
            "notification_channel_binding",
            binding.getId(),
            "secret_value",
            binding.getProvider(),
            binding.getSecretValue(),
            sourceCrypto,
            targetCrypto,
            execute
        );
        items.add(webhookUrl);
        items.add(secret);

        boolean updated = false;
        if (shouldUpdate(webhookUrl, execute)) {
            binding.setWebhookUrlValue(targetCrypto.encrypt(sourceCrypto.decrypt(binding.getWebhookUrlValue())));
            updated = true;
        }
        if (shouldUpdate(secret, execute)) {
            binding.setSecretValue(targetCrypto.encrypt(sourceCrypto.decrypt(binding.getSecretValue())));
            updated = true;
        }
        if (updated) {
            binding.setUpdatedAt(LocalDateTime.now());
            notificationChannelBindingMapper.updateById(binding);
        }
        return items;
    }

    private SecretReEncryptionItemDto processValue(
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
            return item(tableName, recordId, fieldName, provider, "empty", null, targetCrypto, STATUS_SKIPPED_EMPTY, null, "No secret value configured");
        }
        String sourceKeyId = null;
        String sourceFormat = sourceCrypto.ciphertextFormat(value);
        try {
            sourceKeyId = sourceCrypto.encryptedKeyId(value);
            if (targetCrypto.isActiveCiphertext(value)) {
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

    private boolean shouldUpdate(SecretReEncryptionItemDto item, boolean execute) {
        return execute && STATUS_RE_ENCRYPTED.equals(item.status());
    }

    private boolean isFailedStatus(String status) {
        return STATUS_FAILED.equals(status)
            || STATUS_KEY_MISMATCH.equals(status)
            || STATUS_DECRYPT_FAILED.equals(status);
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
