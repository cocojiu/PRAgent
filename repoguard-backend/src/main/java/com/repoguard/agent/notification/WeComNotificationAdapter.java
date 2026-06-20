package com.repoguard.agent.notification;

import com.repoguard.agent.security.SecretCryptoService;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class WeComNotificationAdapter extends AbstractWebhookNotificationAdapter {

    private final WebhookNotificationPayloadFactory payloadFactory;

    public WeComNotificationAdapter(
        RestClient.Builder restClientBuilder,
        SecretCryptoService secretCryptoService,
        WebhookNotificationContentBuilder contentBuilder,
        WebhookNotificationResponseEvaluator responseEvaluator,
        WebhookNotificationPayloadFactory payloadFactory,
        WebhookNotificationRequestFactory requestFactory
    ) {
        super(restClientBuilder, contentBuilder, responseEvaluator, requestFactory);
        this.payloadFactory = payloadFactory;
    }

    @Override
    public String provider() {
        return "WECOM";
    }

    @Override
    protected Object payload(String title, String markdown) {
        return payloadFactory.weComMarkdown(markdown);
    }
}
