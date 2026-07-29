package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.notification.binding.NotificationBindingStatus;
import com.repoguard.agent.security.SecretCryptoService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class NotificationBindingResponseAssemblerTest {

    private final SecretCryptoService secretCryptoService = org.mockito.Mockito.mock(SecretCryptoService.class);
    private final NotificationBindingResponseAssembler assembler =
        new NotificationBindingResponseAssembler(secretCryptoService);

    @Test
    void assembleMasksExistingSecretsAndFormatsTimes() {
        NotificationChannelBinding binding = binding();
        when(secretCryptoService.decrypt("enc:webhook")).thenReturn("https://example.com/webhook");
        when(secretCryptoService.decrypt("enc:secret")).thenReturn("secret");

        var result = assembler.assemble(binding);

        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.name()).isEqualTo("DingTalk");
        assertThat(result.provider()).isEqualTo("DINGTALK");
        assertThat(result.webhookUrl()).isEqualTo("******");
        assertThat(result.secret()).isEqualTo("******");
        assertThat(result.lastCheckedAt()).isEqualTo("2026-06-20 09:55:00");
        assertThat(result.createdAt()).isEqualTo("2026-06-20 09:50:00");
        assertThat(result.updatedAt()).isEqualTo("2026-06-20 09:56:00");
    }

    @Test
    void assembleReturnsNullSecretFieldsWhenEncryptedValuesAreEmpty() {
        NotificationChannelBinding binding = binding();
        binding.setWebhookUrlValue(null);
        binding.setSecretValue("");

        var result = assembler.assemble(binding);

        assertThat(result.webhookUrl()).isNull();
        assertThat(result.secret()).isNull();
        assertThat(result.webhookUrlStatus()).isEqualTo("missing");
        assertThat(result.secretStatus()).isEqualTo("missing");
    }

    @Test
    void assembleReportsBrokenSecretsWithoutThrowing() {
        SecretCryptoService realCrypto = new SecretCryptoService("test-encryption-key");
        NotificationBindingResponseAssembler realAssembler = new NotificationBindingResponseAssembler(realCrypto);
        NotificationChannelBinding binding = binding();
        binding.setWebhookUrlValue("enc:v2:old-key:not-a-real-payload");
        binding.setSecretValue("enc:v2:local:not-a-real-payload");

        var result = realAssembler.assemble(binding);

        assertThat(result.webhookUrl()).isNull();
        assertThat(result.webhookUrlStatus()).isEqualTo("key_mismatch");
        assertThat(result.secret()).isNull();
        assertThat(result.secretStatus()).isEqualTo("decrypt_failed");
    }

    private NotificationChannelBinding binding() {
        NotificationChannelBinding binding = new NotificationChannelBinding();
        binding.setId(1L);
        binding.setName("DingTalk");
        binding.setProvider("DINGTALK");
        binding.setOrganization("octocat");
        binding.setRepository("Hello-World");
        binding.setEnabled(true);
        binding.setWebhookUrlValue("enc:webhook");
        binding.setSecretValue("enc:secret");
        binding.setNotifyReviewCompleted(true);
        binding.setNotifyReviewFailed(true);
        binding.setNotifyHumanReviewRequired(true);
        binding.setNotifyGithubComment(true);
        binding.setStatus(NotificationBindingStatus.CONFIGURED.code());
        binding.setLastCheckedAt(LocalDateTime.of(2026, 6, 20, 9, 55));
        binding.setLastError("timeout");
        binding.setCreatedAt(LocalDateTime.of(2026, 6, 20, 9, 50));
        binding.setUpdatedAt(LocalDateTime.of(2026, 6, 20, 9, 56));
        return binding;
    }
}
