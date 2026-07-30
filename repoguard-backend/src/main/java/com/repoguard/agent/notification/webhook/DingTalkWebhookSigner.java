package com.repoguard.agent.notification.webhook;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class DingTalkWebhookSigner {

    private final Clock clock;

    @Autowired
    DingTalkWebhookSigner() {
        this(Clock.systemUTC());
    }

    DingTalkWebhookSigner(Clock clock) {
        this.clock = clock;
    }

    String signedUrl(String webhookUrl, String secret) {
        if (!StringUtils.hasText(secret)) {
            return webhookUrl;
        }
        try {
            long timestamp = clock.millis();
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

    private byte[] utf8(String value) {
        return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    }
}
