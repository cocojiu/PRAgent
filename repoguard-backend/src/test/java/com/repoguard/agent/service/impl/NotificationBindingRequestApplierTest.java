package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.dto.NotificationBindingRequest;
import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.notification.NotificationBindingStatus;
import com.repoguard.agent.security.SecretCryptoService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class NotificationBindingRequestApplierTest {

    private final SecretCryptoService secretCryptoService = org.mockito.Mockito.mock(SecretCryptoService.class);
    private final NotificationBindingRequestApplier applier = new NotificationBindingRequestApplier(secretCryptoService);

    @Test
    void applyCreateTrimsNormalizesEncryptsAndSetsConfiguredStatus() {
        when(secretCryptoService.encrypt("https://example.com/webhook")).thenReturn("enc:webhook");
        when(secretCryptoService.encrypt("secret")).thenReturn("enc:secret");
        NotificationChannelBinding binding = new NotificationChannelBinding();

        applier.apply(binding, request(" https://example.com/webhook ", " secret "), now(), true);

        assertThat(binding.getName()).isEqualTo("DingTalk");
        assertThat(binding.getProvider()).isEqualTo("DINGTALK");
        assertThat(binding.getOrganization()).isEqualTo("octocat");
        assertThat(binding.getRepository()).isEqualTo("Hello-World");
        assertThat(binding.getEnabled()).isTrue();
        assertThat(binding.getWebhookUrlValue()).isEqualTo("enc:webhook");
        assertThat(binding.getSecretValue()).isEqualTo("enc:secret");
        assertThat(binding.getStatus()).isEqualTo(NotificationBindingStatus.CONFIGURED.code());
        assertThat(binding.getLastError()).isNull();
        assertThat(binding.getUpdatedAt()).isEqualTo(now());
    }

    @Test
    void applyUpdateKeepsExistingSecretsWhenRequestContainsMask() {
        NotificationChannelBinding binding = bindingWithSecrets();
        when(secretCryptoService.decrypt("enc:old-webhook")).thenReturn("https://old.example/webhook");
        when(secretCryptoService.decrypt("enc:old-secret")).thenReturn("old-secret");
        when(secretCryptoService.encrypt("https://old.example/webhook")).thenReturn("enc:new-webhook");
        when(secretCryptoService.encrypt("old-secret")).thenReturn("enc:new-secret");

        applier.apply(binding, request("******", "******"), now(), false);

        assertThat(binding.getWebhookUrlValue()).isEqualTo("enc:new-webhook");
        assertThat(binding.getSecretValue()).isEqualTo("enc:new-secret");
    }

    @Test
    void applyUpdateCanReplaceBrokenExistingSecretsWithoutDecryptingThem() {
        NotificationChannelBinding binding = bindingWithSecrets();
        binding.setWebhookUrlValue("enc:broken-webhook");
        binding.setSecretValue("enc:broken-secret");
        when(secretCryptoService.encrypt("https://new.example/webhook")).thenReturn("enc:new-webhook");
        when(secretCryptoService.encrypt("new-secret")).thenReturn("enc:new-secret");

        applier.apply(binding, request("https://new.example/webhook", "new-secret"), now(), false);

        assertThat(binding.getWebhookUrlValue()).isEqualTo("enc:new-webhook");
        assertThat(binding.getSecretValue()).isEqualTo("enc:new-secret");
    }

    @Test
    void applyUpdatePreservesBrokenExistingSecretsWhenRequestContainsMask() {
        NotificationChannelBinding binding = bindingWithSecrets();
        when(secretCryptoService.decrypt("enc:old-webhook")).thenThrow(new IllegalStateException("Unable to decrypt secret"));
        when(secretCryptoService.decrypt("enc:old-secret")).thenThrow(new IllegalStateException("Unable to decrypt secret"));

        applier.apply(binding, request("******", "******"), now(), false);

        assertThat(binding.getWebhookUrlValue()).isEqualTo("enc:old-webhook");
        assertThat(binding.getSecretValue()).isEqualTo("enc:old-secret");
        assertThat(binding.getStatus()).isEqualTo(NotificationBindingStatus.CONFIGURED.code());
        assertThat(binding.getLastError()).isNull();
    }

    @Test
    void applyRejectsMissingWebhookUrl() {
        NotificationChannelBinding binding = new NotificationChannelBinding();

        assertThatThrownBy(() -> applier.apply(binding, request(" ", null), now(), true))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Webhook URL is required");
    }

    private NotificationBindingRequest request(String webhookUrl, String secret) {
        return new NotificationBindingRequest(
            " DingTalk ",
            " dingtalk ",
            " octocat ",
            " Hello-World ",
            true,
            webhookUrl,
            secret,
            true,
            true,
            true,
            true
        );
    }

    private NotificationChannelBinding bindingWithSecrets() {
        NotificationChannelBinding binding = new NotificationChannelBinding();
        binding.setWebhookUrlValue("enc:old-webhook");
        binding.setSecretValue("enc:old-secret");
        return binding;
    }

    private LocalDateTime now() {
        return LocalDateTime.of(2026, 6, 20, 10, 0);
    }
}
