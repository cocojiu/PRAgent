package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class WebhookNotificationResponseEvaluatorTest {

    private final WebhookNotificationResponseEvaluator evaluator =
        new WebhookNotificationResponseEvaluator(new NotificationTextLimiter());

    @Test
    void errcodeZeroMapResponseIsSuccessful() {
        NotificationSendResult result = evaluator.evaluate(Map.of("errcode", 0, "errmsg", "ok"));

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("errcode=0");
    }

    @Test
    void jsonErrcodeZeroResponseIsSuccessful() {
        NotificationSendResult result = evaluator.evaluate("{\"errcode\":0,\"errmsg\":\"ok\"}");

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("\"errcode\":0");
    }

    @Test
    void okMessageResponseIsSuccessfulIgnoringCase() {
        NotificationSendResult result = evaluator.evaluate("{\"errmsg\":\"OK\"}");

        assertThat(result.success()).isTrue();
    }

    @Test
    void nonSuccessResponseIsFailed() {
        NotificationSendResult result = evaluator.evaluate("{\"errcode\":400,\"errmsg\":\"invalid webhook\"}");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("invalid webhook");
    }

    @Test
    void nullResponseIsFailedWithEmptyBody() {
        NotificationSendResult result = evaluator.evaluate(null);

        assertThat(result.success()).isFalse();
        assertThat(result.message()).isEmpty();
    }

    @Test
    void longResponseBodyIsTruncated() {
        NotificationSendResult result = evaluator.evaluate("x".repeat(600));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).hasSize(512);
    }

    @Test
    void exceptionMessageIsTruncated() {
        NotificationSendResult result = evaluator.failure(new RuntimeException("x".repeat(600)));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).hasSize(512);
    }

    @Test
    void exceptionMessageMasksWebhookSecretsBeforeRecordingFailure() {
        NotificationSendResult result = evaluator.failure(new RuntimeException(
            "I/O error on POST https://oapi.dingtalk.com/robot/send?access_token=raw-token&sign=raw-sign"
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("access_token=****");
        assertThat(result.message()).contains("sign=****");
        assertThat(result.message()).doesNotContain("raw-token", "raw-sign");
    }

    @Test
    void exceptionMessageMasksCredentialFormsBeforeRecordingFailure() {
        NotificationSendResult result = evaluator.failure(new RuntimeException(
            "POST https://user:raw-pass@example.com/webhook failed password=raw-password Authorization: Bearer raw-token"
        ));

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("https://user:****@example.com/webhook");
        assertThat(result.message()).contains("password=****", "Bearer ****");
        assertThat(result.message()).doesNotContain("raw-pass", "raw-password", "raw-token");
    }

    @Test
    void responseMessageMasksAssignedSecretsBeforeRecordingFailure() {
        NotificationSendResult result = evaluator.evaluate("token=raw-token secret:raw-secret errmsg=failed");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("token=****");
        assertThat(result.message()).contains("secret:****");
        assertThat(result.message()).doesNotContain("raw-token", "raw-secret");
    }
}
