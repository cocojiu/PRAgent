package com.repoguard.agent.notification;

import com.repoguard.agent.security.SecretCryptoService;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class WeComNotificationAdapter extends AbstractWebhookNotificationAdapter {

    public WeComNotificationAdapter(RestClient.Builder restClientBuilder, SecretCryptoService secretCryptoService) {
        super(restClientBuilder, secretCryptoService);
    }

    @Override
    public String provider() {
        return "WECOM";
    }

    @Override
    protected Object payload(String title, String markdown) {
        return Map.of(
            "msgtype", "markdown",
            "markdown", Map.of("content", markdown)
        );
    }
}
