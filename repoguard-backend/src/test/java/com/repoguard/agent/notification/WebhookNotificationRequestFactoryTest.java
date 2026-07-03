package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.security.SecretCryptoService;
import org.junit.jupiter.api.Test;

class WebhookNotificationRequestFactoryTest {

    private final SecretCryptoService secretCryptoService = org.mockito.Mockito.mock(SecretCryptoService.class);
    private final WebhookNotificationRequestFactory factory =
        new WebhookNotificationRequestFactory(secretCryptoService);

    @Test
    void createReturnsReadyRequestWithDecryptedWebhookUrlAndSecret() {
        NotificationChannelBinding binding = binding();
        when(secretCryptoService.decrypt("enc:webhook")).thenReturn("https://example.com/webhook");
        when(secretCryptoService.decrypt("enc:secret")).thenReturn("secret");

        WebhookNotificationRequest request = factory.create(binding);

        assertThat(request.ready()).isTrue();
        assertThat(request.webhookUrl()).isEqualTo("https://example.com/webhook");
        assertThat(request.secret()).isEqualTo("secret");
        assertThat(request.failureMessage()).isNull();
    }

    @Test
    void createReturnsFailureRequestWhenWebhookUrlIsEmpty() {
        NotificationChannelBinding binding = binding();
        when(secretCryptoService.decrypt("enc:webhook")).thenReturn(" ");

        WebhookNotificationRequest request = factory.create(binding);

        assertThat(request.ready()).isFalse();
        assertThat(request.failureMessage()).isEqualTo("Webhook URL is empty");
        assertThat(request.webhookUrl()).isNull();
        assertThat(request.secret()).isNull();
    }

    @Test
    void createReturnsFailureRequestWhenWebhookCredentialsCannotBeDecrypted() {
        NotificationChannelBinding binding = binding();
        when(secretCryptoService.decrypt("enc:webhook"))
            .thenThrow(new IllegalStateException("Unable to decrypt secret token=raw-token"));

        WebhookNotificationRequest request = factory.create(binding);

        assertThat(request.ready()).isFalse();
        assertThat(request.failureMessage()).isEqualTo("Webhook credentials cannot be decrypted");
        assertThat(request.failureMessage()).doesNotContain("raw-token");
        assertThat(request.webhookUrl()).isNull();
        assertThat(request.secret()).isNull();
    }

    private NotificationChannelBinding binding() {
        NotificationChannelBinding binding = new NotificationChannelBinding();
        binding.setWebhookUrlValue("enc:webhook");
        binding.setSecretValue("enc:secret");
        return binding;
    }
}
