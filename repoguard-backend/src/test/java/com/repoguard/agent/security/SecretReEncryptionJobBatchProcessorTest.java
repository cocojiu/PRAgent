package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SecretReEncryptionJobBatchProcessorTest {

    private static final String ACTIVE_KEY = "Active-Encryption-Key-2026!Rotate-Primary";
    private static final String OLD_KEY = "Old-Encryption-Key-2026!Rotate-Primary";
    private static final String NEW_KEY = "New-Encryption-Key-2026!Rotate-Primary";

    private final SecretReEncryptionJobMapper jobMapper = Mockito.mock(SecretReEncryptionJobMapper.class);
    private final SecretReEncryptionJobItemMapper itemMapper = Mockito.mock(SecretReEncryptionJobItemMapper.class);
    private final IntegrationConfigMapper integrationMapper = Mockito.mock(IntegrationConfigMapper.class);
    private final ReviewPolicyConfigMapper policyMapper = Mockito.mock(ReviewPolicyConfigMapper.class);
    private final NotificationChannelBindingMapper notificationMapper =
        Mockito.mock(NotificationChannelBindingMapper.class);
    private final SecretCryptoService activeCrypto = new SecretCryptoService(
        ACTIVE_KEY,
        "active-2026",
        "Re-Encryption-Salt-2026!Primary",
        false
    );
    private final SecretReEncryptionJobBatchProcessor processor = new SecretReEncryptionJobBatchProcessor(
        jobMapper,
        itemMapper,
        integrationMapper,
        policyMapper,
        notificationMapper,
        activeCrypto,
        new SecretReEncryptionValueProcessor()
    );

    @Test
    void executeProcessesOneBoundedBatchAndAdvancesToNextTable() {
        SecretCryptoService sourceCrypto = activeCrypto.migrationSource(OLD_KEY, "old-2026");
        SecretReEncryptionJob job = runningJob(sourceCrypto, NEW_KEY, "new-2026", "EXECUTE");
        IntegrationConfig integration = new IntegrationConfig();
        integration.setId(3L);
        integration.setProvider("GITHUB");
        integration.setTokenValue(sourceCrypto.encrypt("ghp_old_secret"));

        when(jobMapper.selectById(7L)).thenReturn(job);
        when(integrationMapper.selectList(any())).thenReturn(List.of(integration));
        when(integrationMapper.update(any(), any())).thenReturn(1);
        when(jobMapper.update(any(), any())).thenReturn(1);

        processor.process(7L, "owner-1");

        SecretCryptoService targetCrypto = activeCrypto.migrationTarget(NEW_KEY, "new-2026");
        assertThat(targetCrypto.decrypt(integration.getTokenValue())).isEqualTo("ghp_old_secret");
        verify(integrationMapper).update(any(), any());
        verify(integrationMapper, Mockito.never()).updateById(any(IntegrationConfig.class));
        verify(itemMapper).insert(any(SecretReEncryptionJobItem.class));
        verify(jobMapper).update(any(), any());
    }

    @Test
    void dryRunDoesNotUpdateSourceRowsButPersistsFieldResult() {
        SecretCryptoService sourceCrypto = activeCrypto.migrationSource(OLD_KEY, "old-2026");
        SecretReEncryptionJob job = runningJob(sourceCrypto, NEW_KEY, "new-2026", "DRY_RUN");
        IntegrationConfig integration = new IntegrationConfig();
        integration.setId(3L);
        integration.setProvider("GITHUB");
        integration.setTokenValue(sourceCrypto.encrypt("ghp_old_secret"));

        when(jobMapper.selectById(7L)).thenReturn(job);
        when(integrationMapper.selectList(any())).thenReturn(List.of(integration));
        when(jobMapper.update(any(), any())).thenReturn(1);

        processor.process(7L, "owner-1");

        verify(integrationMapper, Mockito.never()).update(any(), any());
        verify(integrationMapper, Mockito.never()).updateById(any(IntegrationConfig.class));
        verify(itemMapper).insert(any(SecretReEncryptionJobItem.class));
    }

    @Test
    void executeUpdatesOnlyNotificationSecretFieldsAndPersistsBothResults() {
        SecretCryptoService sourceCrypto = activeCrypto.migrationSource(OLD_KEY, "old-2026");
        SecretReEncryptionJob job = runningJob(sourceCrypto, NEW_KEY, "new-2026", "EXECUTE");
        job.setCurrentTable("notification_channel_binding");
        NotificationChannelBinding binding = new NotificationChannelBinding();
        binding.setId(9L);
        binding.setProvider("DINGTALK");
        binding.setWebhookUrlValue(sourceCrypto.encrypt("https://example.com/webhook"));
        binding.setSecretValue(sourceCrypto.encrypt("signing-secret"));

        when(jobMapper.selectById(7L)).thenReturn(job);
        when(notificationMapper.selectList(any())).thenReturn(List.of(binding));
        when(notificationMapper.update(any(), any())).thenReturn(1);
        when(jobMapper.update(any(), any())).thenReturn(1);

        processor.process(7L, "owner-1");

        SecretCryptoService targetCrypto = activeCrypto.migrationTarget(NEW_KEY, "new-2026");
        assertThat(targetCrypto.decrypt(binding.getWebhookUrlValue())).isEqualTo("https://example.com/webhook");
        assertThat(targetCrypto.decrypt(binding.getSecretValue())).isEqualTo("signing-secret");
        verify(notificationMapper).update(any(), any());
        verify(notificationMapper, Mockito.never()).updateById(any(NotificationChannelBinding.class));
        verify(itemMapper, Mockito.times(2)).insert(any(SecretReEncryptionJobItem.class));
    }

    @Test
    void concurrentSecretChangeAbortsTheBatchInsteadOfOverwritingTheRow() {
        SecretCryptoService sourceCrypto = activeCrypto.migrationSource(OLD_KEY, "old-2026");
        SecretReEncryptionJob job = runningJob(sourceCrypto, NEW_KEY, "new-2026", "EXECUTE");
        IntegrationConfig integration = new IntegrationConfig();
        integration.setId(3L);
        integration.setProvider("GITHUB");
        integration.setTokenValue(sourceCrypto.encrypt("ghp_old_secret"));

        when(jobMapper.selectById(7L)).thenReturn(job);
        when(integrationMapper.selectList(any())).thenReturn(List.of(integration));
        when(integrationMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> processor.process(7L, "owner-1"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("unexpected rows");

        verify(itemMapper, Mockito.never()).insert(any(SecretReEncryptionJobItem.class));
        verify(jobMapper, Mockito.never()).update(any(), any());
    }

    private SecretReEncryptionJob runningJob(
        SecretCryptoService sourceCrypto,
        String targetKey,
        String targetKeyId,
        String mode
    ) {
        SecretReEncryptionJob job = new SecretReEncryptionJob();
        job.setId(7L);
        job.setMode(mode);
        job.setStatus("RUNNING");
        job.setSourceKeyId(sourceCrypto.activeKeyId());
        job.setTargetKeyId(targetKeyId);
        job.setSourceKeyCiphertext(activeCrypto.encrypt(OLD_KEY));
        job.setTargetKeyCiphertext(activeCrypto.encrypt(targetKey));
        job.setCurrentTable("integration_config");
        job.setCheckpointId(0L);
        job.setBatchSize(100);
        job.setScannedCount(0L);
        job.setReEncryptedCount(0L);
        job.setSkippedCount(0L);
        job.setFailedCount(0L);
        job.setClaimedBy("owner-1");
        return job;
    }
}
