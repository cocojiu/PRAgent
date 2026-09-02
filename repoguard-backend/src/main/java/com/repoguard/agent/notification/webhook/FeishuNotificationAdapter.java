package com.repoguard.agent.notification.webhook;

import com.repoguard.agent.external.ExternalHttpResponseReader;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class FeishuNotificationAdapter extends AbstractWebhookNotificationAdapter {

    private final WebhookNotificationPayloadFactory payloadFactory;

    public FeishuNotificationAdapter(
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
    public String provider() { return "FEISHU"; }

    @Override
    protected Object payload(String title, String markdown) { return payloadFactory.feishuText(title + "\n" + markdown); }
}
