package com.repoguard.agent.notification;

import com.repoguard.agent.entity.NotificationChannelBinding;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

abstract class AbstractWebhookNotificationAdapter implements NotificationChannelAdapter {

    private final RestClient restClient;
    private final WebhookNotificationContentBuilder contentBuilder;
    private final WebhookNotificationResponseEvaluator responseEvaluator;
    private final WebhookNotificationRequestFactory requestFactory;

    AbstractWebhookNotificationAdapter(
        RestClient.Builder restClientBuilder,
        WebhookNotificationContentBuilder contentBuilder,
        WebhookNotificationResponseEvaluator responseEvaluator,
        WebhookNotificationRequestFactory requestFactory
    ) {
        SimpleClientHttpRequestFactory httpRequestFactory = new SimpleClientHttpRequestFactory();
        httpRequestFactory.setConnectTimeout(5000);
        httpRequestFactory.setReadTimeout(8000);
        this.restClient = restClientBuilder.requestFactory(httpRequestFactory).build();
        this.contentBuilder = contentBuilder;
        this.responseEvaluator = responseEvaluator;
        this.requestFactory = requestFactory;
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
        WebhookNotificationRequest request = requestFactory.create(binding);
        if (!request.ready()) {
            return NotificationSendResult.failed(null, request.failureMessage());
        }
        try {
            Object response = restClient.post()
                .uri(signedWebhookUrl(request.webhookUrl(), request.secret()))
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
