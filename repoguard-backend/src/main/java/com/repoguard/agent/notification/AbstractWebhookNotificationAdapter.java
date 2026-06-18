package com.repoguard.agent.notification;

import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.security.SecretCryptoService;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

abstract class AbstractWebhookNotificationAdapter implements NotificationChannelAdapter {

    private final RestClient restClient;
    private final SecretCryptoService secretCryptoService;
    private final WebhookNotificationContentBuilder contentBuilder;
    private final WebhookNotificationResponseEvaluator responseEvaluator;

    AbstractWebhookNotificationAdapter(
        RestClient.Builder restClientBuilder,
        SecretCryptoService secretCryptoService,
        WebhookNotificationContentBuilder contentBuilder,
        WebhookNotificationResponseEvaluator responseEvaluator
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(5000);
        requestFactory.setReadTimeout(8000);
        this.restClient = restClientBuilder.requestFactory(requestFactory).build();
        this.secretCryptoService = secretCryptoService;
        this.contentBuilder = contentBuilder;
        this.responseEvaluator = responseEvaluator;
    }

    @Override
    public NotificationSendResult send(NotificationMessage message, NotificationChannelBinding binding) {
        WebhookNotificationContent content = contentBuilder.reviewContent(message);
        return doPost(binding, payload(content.title(), content.markdown()));
    }

    @Override
    public NotificationSendResult test(NotificationChannelBinding binding) {
        WebhookNotificationContent content = contentBuilder.testContent();
        return doPost(binding, payload(content.title(), content.markdown()));
    }

    protected abstract Object payload(String title, String markdown);

    protected NotificationSendResult doPost(NotificationChannelBinding binding, Object payload) {
        String webhookUrl = secretCryptoService.decrypt(binding.getWebhookUrlValue());
        if (!StringUtils.hasText(webhookUrl)) {
            return NotificationSendResult.failed(null, "Webhook URL is empty");
        }
        try {
            Object response = restClient.post()
                .uri(signedWebhookUrl(webhookUrl, secretCryptoService.decrypt(binding.getSecretValue())))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .body(Object.class);
            return responseEvaluator.evaluate(response);
        } catch (RuntimeException ex) {
            return responseEvaluator.failure(ex);
        }
    }

    protected String signedWebhookUrl(String webhookUrl, String secret) {
        return webhookUrl;
    }

    protected byte[] utf8(String value) {
        return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    }

}
