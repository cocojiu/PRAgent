package com.repoguard.agent.notification;

import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.security.SecretCryptoService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class WebhookNotificationRequestFactory {

    private final SecretCryptoService secretCryptoService;

    WebhookNotificationRequestFactory(SecretCryptoService secretCryptoService) {
        this.secretCryptoService = secretCryptoService;
    }

    WebhookNotificationRequest create(NotificationChannelBinding binding) {
        String webhookUrl = secretCryptoService.decrypt(binding.getWebhookUrlValue());
        if (!StringUtils.hasText(webhookUrl)) {
            return new WebhookNotificationRequest(null, null, "Webhook URL is empty");
        }
        return new WebhookNotificationRequest(
            webhookUrl,
            secretCryptoService.decrypt(binding.getSecretValue()),
            null
        );
    }
}
