package com.repoguard.agent.security;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.SecretReEncryptionItemDto;
import com.repoguard.agent.dto.SecretReEncryptionRequest;
import com.repoguard.agent.dto.SecretReEncryptionResponse;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
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

    private final IntegrationConfigMapper integrationConfigMapper;
    private final ReviewPolicyConfigMapper reviewPolicyConfigMapper;

    public SecretReEncryptionService(
        IntegrationConfigMapper integrationConfigMapper,
        ReviewPolicyConfigMapper reviewPolicyConfigMapper
    ) {
        this.integrationConfigMapper = integrationConfigMapper;
        this.reviewPolicyConfigMapper = reviewPolicyConfigMapper;
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
        SecretCryptoService sourceCrypto = new SecretCryptoService(request.sourceEncryptionKey(), sourceKeyId, false);
        SecretCryptoService targetCrypto = new SecretCryptoService(request.targetEncryptionKey(), request.targetKeyId(), true);
        List<SecretReEncryptionItemDto> items = new ArrayList<>();

        for (IntegrationConfig config : integrationConfigMapper.selectList(null)) {
            items.add(processIntegration(config, sourceCrypto, targetCrypto, execute));
        }
        for (ReviewPolicyConfig config : reviewPolicyConfigMapper.selectList(null)) {
            items.add(processReviewPolicy(config, sourceCrypto, targetCrypto, execute));
        }

        int reEncryptedCount = (int) items.stream()
            .filter(item -> STATUS_RE_ENCRYPTED.equals(item.status()) || STATUS_WOULD_RE_ENCRYPT.equals(item.status()))
            .count();
        int failedCount = (int) items.stream().filter(item -> STATUS_FAILED.equals(item.status())).count();
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
            return item(tableName, recordId, fieldName, provider, "empty", null, targetCrypto, STATUS_SKIPPED_EMPTY, "No secret value configured");
        }
        try {
            String sourceKeyId = sourceCrypto.encryptedKeyId(value);
            String sourceFormat = resolveSourceFormat(value, sourceKeyId);
            if (targetCrypto.isVersionedCiphertext(value) && targetCrypto.activeKeyId().equals(targetCrypto.encryptedKeyId(value))) {
                return item(tableName, recordId, fieldName, provider, sourceFormat, sourceKeyId, targetCrypto, STATUS_SKIPPED_TARGET_KEY, "Already encrypted with target key id");
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
                execute ? "Secret was re-encrypted" : "Secret can be re-encrypted"
            );
        } catch (Exception ex) {
            return item(tableName, recordId, fieldName, provider, "unknown", null, targetCrypto, STATUS_FAILED, ex.getMessage());
        }
    }

    private boolean shouldUpdate(SecretReEncryptionItemDto item, boolean execute) {
        return execute && STATUS_RE_ENCRYPTED.equals(item.status());
    }

    private String resolveSourceFormat(String value, String sourceKeyId) {
        if (!StringUtils.hasText(sourceKeyId)) {
            return "plaintext";
        }
        if ("v1".equals(sourceKeyId)) {
            return "enc:v1";
        }
        return "enc:v2";
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
            message
        );
    }
}
