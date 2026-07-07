package com.repoguard.agent.notification;

import com.repoguard.agent.security.SecretCryptoService;
import com.repoguard.agent.external.ExternalHttpResponseReader;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class DingTalkNotificationAdapter extends AbstractWebhookNotificationAdapter {

    private final WebhookNotificationPayloadFactory payloadFactory;
    private final DingTalkWebhookSigner webhookSigner;

    public DingTalkNotificationAdapter(
        RestClient.Builder restClientBuilder,
        SecretCryptoService secretCryptoService,
        WebhookNotificationContentBuilder contentBuilder,
        WebhookNotificationResponseEvaluator responseEvaluator,
        WebhookNotificationPayloadFactory payloadFactory,
        DingTalkWebhookSigner webhookSigner,
        WebhookNotificationRequestFactory requestFactory,
        ExternalHttpResponseReader responseReader
    ) {
        super(restClientBuilder, contentBuilder, responseEvaluator, requestFactory, responseReader);
        this.payloadFactory = payloadFactory;
        this.webhookSigner = webhookSigner;
    }

    @Override
    public String provider() {
        return "DINGTALK";
    }

    @Override
    protected Object payload(String title, String markdown) {
        return payloadFactory.dingTalkMarkdown(title, markdown);
    }

    @Override
    protected String signedWebhookUrl(String webhookUrl, String secret) {
        return webhookSigner.signedUrl(webhookUrl, secret);
    }
}
