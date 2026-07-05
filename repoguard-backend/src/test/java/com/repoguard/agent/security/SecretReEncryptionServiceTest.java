package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.dto.SecretReEncryptionRequest;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.mapper.NotificationChannelBindingMapper;
import com.repoguard.agent.mapper.ReviewPolicyConfigMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SecretReEncryptionServiceTest {

    private static final String OLD_KEY = "Old-Encryption-Key-2026!Rotate-Primary";
    private static final String NEW_KEY = "New-Encryption-Key-2026!Rotate-Primary";
    private static final String OLD_KEY_ID = "old-2026";
    private static final String NEW_KEY_ID = "new-2026";

    private final IntegrationConfigMapper integrationConfigMapper = Mockito.mock(IntegrationConfigMapper.class);
    private final ReviewPolicyConfigMapper reviewPolicyConfigMapper = Mockito.mock(ReviewPolicyConfigMapper.class);
    private final NotificationChannelBindingMapper notificationChannelBindingMapper = Mockito.mock(NotificationChannelBindingMapper.class);
    private final SecretReEncryptionService service = new SecretReEncryptionService(
        integrationConfigMapper,
        reviewPolicyConfigMapper,
        notificationChannelBindingMapper
    );

    @BeforeEach
    void setUp() {
        when(integrationConfigMapper.selectList(isNull())).thenReturn(List.of());
        when(reviewPolicyConfigMapper.selectList(isNull())).thenReturn(List.of());
        when(notificationChannelBindingMapper.selectList(isNull())).thenReturn(List.of());
    }

    @Test
    void dryRunReportsReEncryptableSecretsWithoutUpdatingRows() {
        SecretCryptoService oldCrypto = new SecretCryptoService(OLD_KEY, OLD_KEY_ID, false);
        IntegrationConfig integration = integrationConfig(1L, "GITHUB", oldCrypto.encrypt("ghp_old_secret"));
        ReviewPolicyConfig reviewPolicy = reviewPolicyConfig(1L, "dashscope", "plaintext-api-key");
        when(integrationConfigMapper.selectList(isNull())).thenReturn(List.of(integration));
        when(reviewPolicyConfigMapper.selectList(isNull())).thenReturn(List.of(reviewPolicy));

        var result = service.reEncrypt(request(false, null));

        assertThat(result.executed()).isFalse();
        assertThat(result.scannedCount()).isEqualTo(2);
        assertThat(result.reEncryptedCount()).isEqualTo(2);
        assertThat(result.failedCount()).isZero();
        assertThat(result.items()).extracting("status").containsOnly("WOULD_RE_ENCRYPT");
        verify(integrationConfigMapper, never()).updateById(Mockito.any(IntegrationConfig.class));
        verify(reviewPolicyConfigMapper, never()).updateById(Mockito.any(ReviewPolicyConfig.class));
    }

    @Test
    void dryRunReportsNotificationBindingSecretsWithoutUpdatingRows() {
        SecretCryptoService oldCrypto = new SecretCryptoService(OLD_KEY, OLD_KEY_ID, false);
        NotificationChannelBinding binding = notificationBinding(
            1L,
            "DINGTALK",
            oldCrypto.encrypt("https://example.com/webhook"),
            oldCrypto.encrypt("signing-secret")
        );
        when(notificationChannelBindingMapper.selectList(isNull())).thenReturn(List.of(binding));

        var result = service.reEncrypt(request(false, null));

        assertThat(result.executed()).isFalse();
        assertThat(result.scannedCount()).isEqualTo(2);
        assertThat(result.reEncryptedCount()).isEqualTo(2);
        assertThat(result.failedCount()).isZero();
        assertThat(result.items()).extracting("tableName").containsOnly("notification_channel_binding");
        assertThat(result.items()).extracting("fieldName").containsExactly("webhook_url_value", "secret_value");
        assertThat(result.items()).extracting("status").containsOnly("WOULD_RE_ENCRYPT");
        verify(notificationChannelBindingMapper, never()).updateById(Mockito.any(NotificationChannelBinding.class));
    }

    @Test
    void executeReEncryptsNotificationBindingSecretsWithTargetKey() {
        SecretCryptoService oldCrypto = new SecretCryptoService(OLD_KEY, OLD_KEY_ID, false);
        SecretCryptoService newCrypto = new SecretCryptoService(NEW_KEY, NEW_KEY_ID, false);
        NotificationChannelBinding binding = notificationBinding(
            1L,
            "WECOM",
            oldCrypto.encrypt("https://example.com/wecom"),
            oldCrypto.encrypt("wecom-secret")
        );
        when(notificationChannelBindingMapper.selectList(isNull())).thenReturn(List.of(binding));

        var result = service.reEncrypt(request(true, "RE-ENCRYPT"));

        assertThat(result.executed()).isTrue();
        assertThat(result.scannedCount()).isEqualTo(2);
        assertThat(result.reEncryptedCount()).isEqualTo(2);
        assertThat(binding.getWebhookUrlValue()).startsWith("enc:v2:" + NEW_KEY_ID + ":");
        assertThat(binding.getSecretValue()).startsWith("enc:v2:" + NEW_KEY_ID + ":");
        assertThat(newCrypto.decrypt(binding.getWebhookUrlValue())).isEqualTo("https://example.com/wecom");
        assertThat(newCrypto.decrypt(binding.getSecretValue())).isEqualTo("wecom-secret");
        assertThat(binding.getUpdatedAt()).isNotNull();
        verify(notificationChannelBindingMapper).updateById(binding);
    }

    @Test
    void dryRunReportsNotificationBindingKeyMismatchAndDamagedCiphertext() {
        NotificationChannelBinding binding = notificationBinding(
            1L,
            "DINGTALK",
            "enc:v2:legacy-key:not-a-real-payload",
            "enc:v2:" + OLD_KEY_ID + ":not-a-real-payload"
        );
        when(notificationChannelBindingMapper.selectList(isNull())).thenReturn(List.of(binding));

        var result = service.reEncrypt(request(false, null));

        assertThat(result.failedCount()).isEqualTo(2);
        assertThat(result.items()).extracting("fieldName").containsExactly("webhook_url_value", "secret_value");
        assertThat(result.items()).extracting("status").containsExactly("KEY_MISMATCH", "DECRYPT_FAILED");
        assertThat(result.items().getFirst().sourceKeyId()).isEqualTo("legacy-key");
        assertThat(result.items().get(1).sourceKeyId()).isEqualTo(OLD_KEY_ID);
        verify(notificationChannelBindingMapper, never()).updateById(Mockito.any(NotificationChannelBinding.class));
    }

    @Test
    void executeReEncryptsSecretsWithTargetKey() {
        SecretCryptoService oldCrypto = new SecretCryptoService(OLD_KEY, OLD_KEY_ID, false);
        SecretCryptoService newCrypto = new SecretCryptoService(NEW_KEY, NEW_KEY_ID, false);
        IntegrationConfig integration = integrationConfig(1L, "GITHUB", oldCrypto.encrypt("ghp_old_secret"));
        ReviewPolicyConfig reviewPolicy = reviewPolicyConfig(1L, "dashscope", "plaintext-api-key");
        when(integrationConfigMapper.selectList(isNull())).thenReturn(List.of(integration));
        when(reviewPolicyConfigMapper.selectList(isNull())).thenReturn(List.of(reviewPolicy));

        var result = service.reEncrypt(request(true, "RE-ENCRYPT"));

        assertThat(result.executed()).isTrue();
        assertThat(result.reEncryptedCount()).isEqualTo(2);
        assertThat(result.failedCount()).isZero();
        assertThat(integration.getTokenValue()).startsWith("enc:v2:" + NEW_KEY_ID + ":");
        assertThat(reviewPolicy.getApiKeyValue()).startsWith("enc:v2:" + NEW_KEY_ID + ":");
        assertThat(newCrypto.decrypt(integration.getTokenValue())).isEqualTo("ghp_old_secret");
        assertThat(newCrypto.decrypt(reviewPolicy.getApiKeyValue())).isEqualTo("plaintext-api-key");
        verify(integrationConfigMapper).updateById(integration);
        verify(reviewPolicyConfigMapper).updateById(reviewPolicy);
    }

    @Test
    void executeRequiresConfirmationText() {
        assertThatThrownBy(() -> service.reEncrypt(request(true, "WRONG")))
            .isInstanceOf(BusinessException.class)
            .hasMessage("confirmText must be RE-ENCRYPT when execute is true");
    }

    @Test
    void skipsSecretsAlreadyEncryptedWithTargetKey() {
        SecretCryptoService newCrypto = new SecretCryptoService(NEW_KEY, NEW_KEY_ID, false);
        IntegrationConfig integration = integrationConfig(1L, "GITHUB", newCrypto.encrypt("ghp_current_secret"));
        when(integrationConfigMapper.selectList(isNull())).thenReturn(List.of(integration));
        when(reviewPolicyConfigMapper.selectList(isNull())).thenReturn(List.of());

        var result = service.reEncrypt(request(true, "RE-ENCRYPT"));

        assertThat(result.reEncryptedCount()).isZero();
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.items().getFirst().status()).isEqualTo("SKIPPED_TARGET_KEY");
        verify(integrationConfigMapper, never()).updateById(Mockito.any(IntegrationConfig.class));
    }

    @Test
    void dryRunReportsKeyMismatchAndDamagedCiphertext() {
        IntegrationConfig mismatched = integrationConfig(1L, "GITHUB", "enc:v2:legacy-key:not-a-real-payload");
        ReviewPolicyConfig damaged = reviewPolicyConfig(1L, "dashscope", "enc:v2:" + OLD_KEY_ID + ":not-a-real-payload");
        when(integrationConfigMapper.selectList(isNull())).thenReturn(List.of(mismatched));
        when(reviewPolicyConfigMapper.selectList(isNull())).thenReturn(List.of(damaged));

        var result = service.reEncrypt(request(false, null));

        assertThat(result.failedCount()).isEqualTo(2);
        assertThat(result.items()).extracting("status").containsExactly("KEY_MISMATCH", "DECRYPT_FAILED");
        assertThat(result.items().getFirst().sourceKeyId()).isEqualTo("legacy-key");
        assertThat(result.items().getFirst().targetKeyId()).isEqualTo(NEW_KEY_ID);
        assertThat(result.items().get(1).sourceKeyId()).isEqualTo(OLD_KEY_ID);
        verify(integrationConfigMapper, never()).updateById(Mockito.any(IntegrationConfig.class));
        verify(reviewPolicyConfigMapper, never()).updateById(Mockito.any(ReviewPolicyConfig.class));
    }

    private SecretReEncryptionRequest request(boolean execute, String confirmText) {
        return new SecretReEncryptionRequest(
            OLD_KEY,
            OLD_KEY_ID,
            NEW_KEY,
            NEW_KEY_ID,
            execute,
            confirmText
        );
    }

    private IntegrationConfig integrationConfig(Long id, String provider, String tokenValue) {
        IntegrationConfig config = new IntegrationConfig();
        config.setId(id);
        config.setProvider(provider);
        config.setStatus("CONFIGURED");
        config.setBaseUrl("https://api.github.com");
        config.setTokenValue(tokenValue);
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        return config;
    }

    private ReviewPolicyConfig reviewPolicyConfig(Long id, String provider, String apiKeyValue) {
        ReviewPolicyConfig config = new ReviewPolicyConfig();
        config.setId(id);
        config.setLlmEnabled(true);
        config.setLlmProvider(provider);
        config.setModelName("qwen-plus");
        config.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        config.setApiKeyValue(apiKeyValue);
        config.setTimeoutSeconds(60);
        config.setTemperature(BigDecimal.valueOf(0.20));
        config.setMaxTokens(4096);
        config.setFallbackToRules(true);
        config.setWorkerConcurrency(1);
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        return config;
    }

    private NotificationChannelBinding notificationBinding(Long id, String provider, String webhookUrlValue, String secretValue) {
        NotificationChannelBinding binding = new NotificationChannelBinding();
        binding.setId(id);
        binding.setName(provider + " binding");
        binding.setProvider(provider);
        binding.setOrganization("octocat");
        binding.setRepository("Hello-World");
        binding.setEnabled(true);
        binding.setWebhookUrlValue(webhookUrlValue);
        binding.setSecretValue(secretValue);
        binding.setCreatedAt(LocalDateTime.now());
        binding.setUpdatedAt(LocalDateTime.now());
        return binding;
    }
}
