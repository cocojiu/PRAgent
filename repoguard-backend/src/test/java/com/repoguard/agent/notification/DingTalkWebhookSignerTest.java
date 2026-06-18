package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class DingTalkWebhookSignerTest {

    private final Clock clock = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);
    private final DingTalkWebhookSigner signer = new DingTalkWebhookSigner(clock);

    @Test
    void returnsOriginalUrlWhenSecretIsBlank() {
        assertThat(signer.signedUrl("https://oapi.dingtalk.com/robot/send", null))
            .isEqualTo("https://oapi.dingtalk.com/robot/send");
        assertThat(signer.signedUrl("https://oapi.dingtalk.com/robot/send", "  "))
            .isEqualTo("https://oapi.dingtalk.com/robot/send");
    }

    @Test
    void appendsSignatureWithQuestionMarkForPlainUrl() throws Exception {
        String signedUrl = signer.signedUrl("https://oapi.dingtalk.com/robot/send", "secret");

        assertThat(signedUrl).isEqualTo(
            "https://oapi.dingtalk.com/robot/send?timestamp=1700000000000&sign=" + expectedSign("secret")
        );
    }

    @Test
    void appendsSignatureWithAmpersandForUrlWithQueryString() throws Exception {
        String signedUrl = signer.signedUrl("https://oapi.dingtalk.com/robot/send?access_token=abc", "secret");

        assertThat(signedUrl).isEqualTo(
            "https://oapi.dingtalk.com/robot/send?access_token=abc&timestamp=1700000000000&sign=" + expectedSign("secret")
        );
    }

    private String expectedSign(String secret) throws Exception {
        String stringToSign = "1700000000000\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return URLEncoder.encode(
            Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8))),
            StandardCharsets.UTF_8
        );
    }
}
