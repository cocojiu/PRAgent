package com.repoguard.agent.notification.webhook;

import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.external.ExternalHttpRequestFactory;
import com.repoguard.agent.external.ExternalHttpResponseProfile;
import com.repoguard.agent.external.ExternalHttpResponseReader;
import com.repoguard.agent.notification.NotificationChannelAdapter;
import com.repoguard.agent.notification.NotificationMessage;
import com.repoguard.agent.notification.delivery.NotificationSendResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

abstract class AbstractWebhookNotificationAdapter implements NotificationChannelAdapter {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(8);

    private final RestClient restClient;
    private final WebhookNotificationContentBuilder contentBuilder;
    private final WebhookNotificationResponseEvaluator responseEvaluator;
    private final WebhookNotificationRequestFactory requestFactory;
    private final ExternalHttpResponseReader responseReader;

    AbstractWebhookNotificationAdapter(
        RestClient.Builder restClientBuilder,
        WebhookNotificationContentBuilder contentBuilder,
        WebhookNotificationResponseEvaluator responseEvaluator,
        WebhookNotificationRequestFactory requestFactory,
        ExternalHttpResponseReader responseReader
    ) {
        this.restClient = restClientBuilder
            .clone()
            .requestFactory(ExternalHttpRequestFactory.simple(CONNECT_TIMEOUT, READ_TIMEOUT))
            .build();
        this.contentBuilder = Objects.requireNonNull(contentBuilder, "contentBuilder");
        this.responseEvaluator = Objects.requireNonNull(responseEvaluator, "responseEvaluator");
        this.requestFactory = Objects.requireNonNull(requestFactory, "requestFactory");
        this.responseReader = Objects.requireNonNull(responseReader, "responseReader");
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
            byte[] response = restClient.post()
                .uri(signedWebhookUrl(request.webhookUrl(), request.secret()))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(payload)
                .exchange((httpRequest, httpResponse) -> responseReader.readSuccessfulBody(
                    httpResponse,
                    "Webhook HTTP request failed",
                    ExternalHttpResponseProfile.NOTIFICATION
                ));
            return responseEvaluator.evaluate(response == null ? "" : new String(response, StandardCharsets.UTF_8));
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
