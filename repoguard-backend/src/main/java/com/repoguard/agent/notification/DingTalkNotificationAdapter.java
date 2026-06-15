package com.repoguard.agent.notification;

import com.repoguard.agent.security.SecretCryptoService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Component
public class DingTalkNotificationAdapter extends AbstractWebhookNotificationAdapter {

    public DingTalkNotificationAdapter(RestClient.Builder restClientBuilder, SecretCryptoService secretCryptoService) {
        super(restClientBuilder, secretCryptoService);
    }

    @Override
    public String provider() {
        return "DINGTALK";
    }

    @Override
    protected Object payload(String title, String markdown) {
        return Map.of(
            "msgtype", "markdown",
            "markdown", markdownMap(title, markdown)
        );
    }

    @Override
    protected String signedWebhookUrl(String webhookUrl, String secret) {
        if (!StringUtils.hasText(secret)) {
            return webhookUrl;
        }
        try {
            long timestamp = System.currentTimeMillis();
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(utf8(secret), "HmacSHA256"));
            String sign = URLEncoder.encode(Base64.getEncoder().encodeToString(mac.doFinal(utf8(stringToSign))), StandardCharsets.UTF_8);
            String separator = webhookUrl.contains("?") ? "&" : "?";
            return webhookUrl + separator + "timestamp=" + timestamp + "&sign=" + sign;
        } catch (Exception ex) {
            return webhookUrl;
        }
    }
}
