package com.repoguard.agent.notification.webhook;

import com.repoguard.agent.external.ExternalHttpResponseReader;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class WeComNotificationAdapter extends AbstractWebhookNotificationAdapter {

    private final WebhookNotificationPayloadFactory payloadFactory;

    public WeComNotificationAdapter(
        RestClient.Builder restClientBuilder,
        WebhookNotificationContentBuilder contentBuilder,
        WebhookNotificationResponseEvaluator responseEvaluator,
        WebhookNotificationPayloadFactory payloadFactory,
        WebhookNotificationRequestFactory requestFactory,
        ExternalHttpResponseReader responseReader
    ) {
        super(restClientBuilder, contentBuilder, responseEvaluator, requestFactory, responseReader);
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
