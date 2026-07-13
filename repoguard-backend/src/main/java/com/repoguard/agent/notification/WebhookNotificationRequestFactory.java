package com.repoguard.agent.notification;

import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.external.OutboundEndpointPolicy;
import com.repoguard.agent.external.OutboundEndpointType;
import com.repoguard.agent.security.SecretCryptoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class WebhookNotificationRequestFactory {

    private static final String DECRYPTION_FAILURE_MESSAGE = "Webhook credentials cannot be decrypted";

    private final SecretCryptoService secretCryptoService;
    private final OutboundEndpointPolicy endpointPolicy;

    WebhookNotificationRequestFactory(SecretCryptoService secretCryptoService) {
        this(secretCryptoService, null);
    }

    @Autowired
    WebhookNotificationRequestFactory(
        SecretCryptoService secretCryptoService,
        OutboundEndpointPolicy endpointPolicy
    ) {
        this.secretCryptoService = secretCryptoService;
        this.endpointPolicy = endpointPolicy;
    }

    WebhookNotificationRequest create(NotificationChannelBinding binding) {
        try {
            String webhookUrl = secretCryptoService.decrypt(binding.getWebhookUrlValue());
            if (!StringUtils.hasText(webhookUrl)) {
                return new WebhookNotificationRequest(null, null, "Webhook URL is empty");
            }
            if (endpointPolicy != null) {
                endpointPolicy.validate(OutboundEndpointType.NOTIFICATION, webhookUrl);
            }
            return new WebhookNotificationRequest(
                webhookUrl,
                secretCryptoService.decrypt(binding.getSecretValue()),
                null
            );
        } catch (RuntimeException ex) {
            return new WebhookNotificationRequest(null, null, DECRYPTION_FAILURE_MESSAGE);
        }
    }
}
