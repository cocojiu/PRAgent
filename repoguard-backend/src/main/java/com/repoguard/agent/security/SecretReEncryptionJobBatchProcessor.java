package com.repoguard.agent.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.dto.SecretReEncryptionItemDto;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.entity.SecretReEncryptionJob;
import com.repoguard.agent.entity.SecretReEncryptionJobItem;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.mapper.NotificationChannelBindingMapper;
import com.repoguard.agent.mapper.ReviewPolicyConfigMapper;
import com.repoguard.agent.mapper.SecretReEncryptionJobItemMapper;
import com.repoguard.agent.mapper.SecretReEncryptionJobMapper;
import com.repoguard.agent.tenancy.ScheduledJobLeaseContext;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SecretReEncryptionJobBatchProcessor {

    private final SecretReEncryptionJobMapper jobMapper;
    private final SecretReEncryptionJobItemMapper itemMapper;
    private final IntegrationConfigMapper integrationConfigMapper;
    private final ReviewPolicyConfigMapper reviewPolicyConfigMapper;
    private final NotificationChannelBindingMapper notificationChannelBindingMapper;
    private final SecretCryptoService activeCrypto;
    private final SecretReEncryptionValueProcessor valueProcessor;

    public SecretReEncryptionJobBatchProcessor(
        SecretReEncryptionJobMapper jobMapper,
        SecretReEncryptionJobItemMapper itemMapper,
        IntegrationConfigMapper integrationConfigMapper,
        ReviewPolicyConfigMapper reviewPolicyConfigMapper,
        NotificationChannelBindingMapper notificationChannelBindingMapper,
        SecretCryptoService activeCrypto,
        SecretReEncryptionValueProcessor valueProcessor
    ) {
        this.jobMapper = Objects.requireNonNull(jobMapper, "jobMapper");
        this.itemMapper = Objects.requireNonNull(itemMapper, "itemMapper");
        this.integrationConfigMapper = Objects.requireNonNull(integrationConfigMapper, "integrationConfigMapper");
        this.reviewPolicyConfigMapper = Objects.requireNonNull(reviewPolicyConfigMapper, "reviewPolicyConfigMapper");
        this.notificationChannelBindingMapper = Objects.requireNonNull(
            notificationChannelBindingMapper,
            "notificationChannelBindingMapper"
        );
        this.activeCrypto = Objects.requireNonNull(activeCrypto, "activeCrypto");
        this.valueProcessor = Objects.requireNonNull(valueProcessor, "valueProcessor");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(Long jobId, String ownerId) {
        ScheduledJobLeaseContext.assertHeld();
        SecretReEncryptionJob job = jobMapper.selectById(jobId);
        assertClaim(job, ownerId);

        SecretCryptoService sourceCrypto = activeCrypto.migrationSource(
            activeCrypto.decrypt(job.getSourceKeyCiphertext()),
            job.getSourceKeyId()
        );
        SecretCryptoService targetCrypto = activeCrypto.migrationTarget(
            activeCrypto.decrypt(job.getTargetKeyCiphertext()),
            job.getTargetKeyId()
        );
        SecretReEncryptionJobTable table = SecretReEncryptionJobTable.fromValue(job.getCurrentTable());
        List<SecretReEncryptionItemDto> items = new ArrayList<>();
        long lastRecordId = longValue(job.getCheckpointId());
        int batchSize = Math.max(1, job.getBatchSize());
        int rowCount;

        switch (table) {
            case INTEGRATION_CONFIG -> {
                List<IntegrationConfig> rows = integrationConfigMapper.selectList(
                    new LambdaQueryWrapper<IntegrationConfig>()
                        .gt(IntegrationConfig::getId, lastRecordId)
                        .orderByAsc(IntegrationConfig::getId)
                        .last("limit " + batchSize)
                );
                rowCount = rows.size();
                for (IntegrationConfig row : rows) {
                    ScheduledJobLeaseContext.assertHeld();
                    items.add(processIntegration(row, sourceCrypto, targetCrypto, isExecute(job)));
                    lastRecordId = row.getId();
                }
            }
            case REVIEW_POLICY_CONFIG -> {
                List<ReviewPolicyConfig> rows = reviewPolicyConfigMapper.selectList(
                    new LambdaQueryWrapper<ReviewPolicyConfig>()
                        .gt(ReviewPolicyConfig::getId, lastRecordId)
                        .orderByAsc(ReviewPolicyConfig::getId)
                        .last("limit " + batchSize)
                );
                rowCount = rows.size();
                for (ReviewPolicyConfig row : rows) {
                    ScheduledJobLeaseContext.assertHeld();
                    items.add(processReviewPolicy(row, sourceCrypto, targetCrypto, isExecute(job)));
                    lastRecordId = row.getId();
                }
            }
            case NOTIFICATION_CHANNEL_BINDING -> {
                List<NotificationChannelBinding> rows = notificationChannelBindingMapper.selectList(
                    new LambdaQueryWrapper<NotificationChannelBinding>()
                        .gt(NotificationChannelBinding::getId, lastRecordId)
                        .orderByAsc(NotificationChannelBinding::getId)
                        .last("limit " + batchSize)
                );
                rowCount = rows.size();
                for (NotificationChannelBinding row : rows) {
                    ScheduledJobLeaseContext.assertHeld();
                    items.addAll(processNotificationBinding(row, sourceCrypto, targetCrypto, isExecute(job)));
                    lastRecordId = row.getId();
                }
            }
            case DONE -> {
                complete(job, ownerId, false);
                return;
            }
            default -> throw new IllegalStateException("Unsupported secret re-encryption table: " + table);
        }

        persistItems(job, items);
        applyProgress(job, ownerId, table, rowCount, lastRecordId, items);
    }

    private SecretReEncryptionItemDto processIntegration(
        IntegrationConfig row,
        SecretCryptoService sourceCrypto,
        SecretCryptoService targetCrypto,
        boolean execute
    ) {
        SecretReEncryptionItemDto item = valueProcessor.inspect(
            "integration_config",
            row.getId(),
            "token_value",
            row.getProvider(),
            row.getTokenValue(),
            sourceCrypto,
            targetCrypto,
            execute
        );
        if (valueProcessor.shouldUpdate(item, execute)) {
            String sourceValue = row.getTokenValue();
            String targetValue = valueProcessor.reEncrypt(sourceValue, sourceCrypto, targetCrypto);
            requireOneRowUpdated(
                integrationConfigMapper.update(
                    null,
                    new UpdateWrapper<IntegrationConfig>()
                        .eq("id", row.getId())
                        .eq("token_value", sourceValue)
                        .set("token_value", targetValue)
                        .set("updated_at", LocalDateTime.now())
                ),
                "integration_config",
                row.getId()
            );
            row.setTokenValue(targetValue);
        }
        return item;
    }

    private SecretReEncryptionItemDto processReviewPolicy(
        ReviewPolicyConfig row,
        SecretCryptoService sourceCrypto,
        SecretCryptoService targetCrypto,
        boolean execute
    ) {
        SecretReEncryptionItemDto item = valueProcessor.inspect(
            "review_policy_config",
            row.getId(),
            "api_key_value",
            row.getLlmProvider(),
            row.getApiKeyValue(),
            sourceCrypto,
            targetCrypto,
            execute
        );
        if (valueProcessor.shouldUpdate(item, execute)) {
            String sourceValue = row.getApiKeyValue();
            String targetValue = valueProcessor.reEncrypt(sourceValue, sourceCrypto, targetCrypto);
            requireOneRowUpdated(
                reviewPolicyConfigMapper.update(
                    null,
                    new UpdateWrapper<ReviewPolicyConfig>()
                        .eq("id", row.getId())
                        .eq("api_key_value", sourceValue)
                        .set("api_key_value", targetValue)
                        .set("updated_at", LocalDateTime.now())
                ),
                "review_policy_config",
                row.getId()
            );
            row.setApiKeyValue(targetValue);
        }
        return item;
    }

    private List<SecretReEncryptionItemDto> processNotificationBinding(
        NotificationChannelBinding row,
        SecretCryptoService sourceCrypto,
        SecretCryptoService targetCrypto,
        boolean execute
    ) {
        SecretReEncryptionItemDto webhookUrl = valueProcessor.inspect(
            "notification_channel_binding",
            row.getId(),
            "webhook_url_value",
            row.getProvider(),
            row.getWebhookUrlValue(),
            sourceCrypto,
            targetCrypto,
            execute
        );
        SecretReEncryptionItemDto secret = valueProcessor.inspect(
            "notification_channel_binding",
            row.getId(),
            "secret_value",
            row.getProvider(),
            row.getSecretValue(),
            sourceCrypto,
            targetCrypto,
            execute
        );
        boolean updated = false;
        UpdateWrapper<NotificationChannelBinding> update = new UpdateWrapper<NotificationChannelBinding>()
            .eq("id", row.getId());
        if (valueProcessor.shouldUpdate(webhookUrl, execute)) {
            String sourceValue = row.getWebhookUrlValue();
            String targetValue = valueProcessor.reEncrypt(sourceValue, sourceCrypto, targetCrypto);
            update.eq("webhook_url_value", sourceValue).set("webhook_url_value", targetValue);
            row.setWebhookUrlValue(targetValue);
            updated = true;
        }
        if (valueProcessor.shouldUpdate(secret, execute)) {
            String sourceValue = row.getSecretValue();
            String targetValue = valueProcessor.reEncrypt(sourceValue, sourceCrypto, targetCrypto);
            update.eq("secret_value", sourceValue).set("secret_value", targetValue);
            row.setSecretValue(targetValue);
            updated = true;
        }
        if (updated) {
            update.set("updated_at", LocalDateTime.now());
            requireOneRowUpdated(
                notificationChannelBindingMapper.update(null, update),
                "notification_channel_binding",
                row.getId()
            );
        }
        return List.of(webhookUrl, secret);
    }

    private void persistItems(SecretReEncryptionJob job, List<SecretReEncryptionItemDto> items) {
        LocalDateTime now = LocalDateTime.now();
        for (SecretReEncryptionItemDto item : items) {
            SecretReEncryptionJobItem entity = new SecretReEncryptionJobItem();
            entity.setJobId(job.getId());
            entity.setTableName(item.tableName());
            entity.setRecordId(item.recordId());
            entity.setFieldName(item.fieldName());
            entity.setProvider(item.provider());
            entity.setSourceFormat(item.sourceFormat());
            entity.setSourceKeyId(item.sourceKeyId());
            entity.setTargetKeyId(item.targetKeyId());
            entity.setStatus(item.status());
            entity.setFailureReason(item.failureReason());
            entity.setMessage(item.message());
            entity.setCreatedAt(now);
            itemMapper.insert(entity);
        }
    }

    private void applyProgress(
        SecretReEncryptionJob job,
        String ownerId,
        SecretReEncryptionJobTable table,
        int rowCount,
        long lastRecordId,
        List<SecretReEncryptionItemDto> items
    ) {
        long scanned = longValue(job.getScannedCount()) + items.size();
        long reEncrypted = longValue(job.getReEncryptedCount())
            + items.stream().filter(item -> SecretReEncryptionValueProcessor.STATUS_RE_ENCRYPTED.equals(item.status())
                || SecretReEncryptionValueProcessor.STATUS_WOULD_RE_ENCRYPT.equals(item.status())).count();
        long failed = longValue(job.getFailedCount())
            + items.stream().filter(item -> valueProcessor.isFailure(item.status())).count();
        long skipped = longValue(job.getSkippedCount()) + items.size()
            - (reEncrypted - longValue(job.getReEncryptedCount()))
            - (failed - longValue(job.getFailedCount()));

        boolean tableExhausted = rowCount < Math.max(1, job.getBatchSize());
        SecretReEncryptionJobTable nextTable = tableExhausted ? table.next() : table;
        boolean completed = nextTable == SecretReEncryptionJobTable.DONE;
        LocalDateTime now = LocalDateTime.now();
        UpdateWrapper<SecretReEncryptionJob> update = new UpdateWrapper<SecretReEncryptionJob>()
            .eq("id", job.getId())
            .eq("status", SecretReEncryptionJobStatus.RUNNING.name())
            .eq("claimed_by", ownerId)
            .set("status", completed
                ? (failed > 0
                    ? SecretReEncryptionJobStatus.COMPLETED_WITH_ERRORS.name()
                    : SecretReEncryptionJobStatus.COMPLETED.name())
                : SecretReEncryptionJobStatus.PENDING.name())
            .set("current_table", nextTable.value())
            .set("checkpoint_id", completed || tableExhausted ? 0L : lastRecordId)
            .set("scanned_count", scanned)
            .set("re_encrypted_count", reEncrypted)
            .set("skipped_count", skipped)
            .set("failed_count", failed)
            .set("retry_count", 0)
            .set("next_retry_at", null)
            .set("claimed_by", null)
            .set("claimed_at", null)
            .set("lease_until", null)
            .set("last_failure_reason", null)
            .set("last_failure_message", null)
            .set("updated_at", now);
        if (completed) {
            update.set("source_key_ciphertext", null)
                .set("target_key_ciphertext", null)
                .set("completed_at", now);
        }
        if (jobMapper.update(null, update) != 1) {
            throw new SecretReEncryptionClaimLostException(job.getId());
        }
    }

    private void complete(SecretReEncryptionJob job, String ownerId, boolean withErrors) {
        LocalDateTime now = LocalDateTime.now();
        UpdateWrapper<SecretReEncryptionJob> update = new UpdateWrapper<SecretReEncryptionJob>()
            .eq("id", job.getId())
            .eq("status", SecretReEncryptionJobStatus.RUNNING.name())
            .eq("claimed_by", ownerId)
            .set("status", withErrors
                ? SecretReEncryptionJobStatus.COMPLETED_WITH_ERRORS.name()
                : SecretReEncryptionJobStatus.COMPLETED.name())
            .set("current_table", SecretReEncryptionJobTable.DONE.value())
            .set("source_key_ciphertext", null)
            .set("target_key_ciphertext", null)
            .set("claimed_by", null)
            .set("claimed_at", null)
            .set("lease_until", null)
            .set("completed_at", now)
            .set("updated_at", now);
        if (jobMapper.update(null, update) != 1) {
            throw new SecretReEncryptionClaimLostException(job.getId());
        }
    }

    private boolean isExecute(SecretReEncryptionJob job) {
        return SecretReEncryptionJobMode.EXECUTE.name().equals(job.getMode());
    }

    private void assertClaim(SecretReEncryptionJob job, String ownerId) {
        if (job == null
            || !SecretReEncryptionJobStatus.RUNNING.name().equals(job.getStatus())
            || !Objects.equals(ownerId, job.getClaimedBy())) {
            throw new SecretReEncryptionClaimLostException(job == null ? null : job.getId());
        }
    }

    private long longValue(Long value) {
        return value == null ? 0L : value;
    }

    private void requireOneRowUpdated(int affectedRows, String table, Long id) {
        if (affectedRows != 1) {
            throw new IllegalStateException("Secret re-encryption update affected unexpected rows table=" + table + " id=" + id);
        }
    }
}
