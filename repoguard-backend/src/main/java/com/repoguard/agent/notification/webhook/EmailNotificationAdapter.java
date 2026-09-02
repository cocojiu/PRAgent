package com.repoguard.agent.notification.webhook;

import com.repoguard.agent.external.ExternalHttpResponseReader;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Sends an email-gateway JSON request; the binding URL points at the approved gateway endpoint. */
@Component
public class EmailNotificationAdapter extends AbstractWebhookNotificationAdapter {

    private final WebhookNotificationPayloadFactory payloadFactory;

    public EmailNotificationAdapter(
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
    public String provider() { return "EMAIL"; }

    @Override
    protected Object payload(String title, String markdown) { return payloadFactory.emailMessage(title, markdown); }
}
