package com.repoguard.agent.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MessagePublishFailureSanitizerTest {

    @Test
    void masksCommonSecretsInPublishFailureMessages() {
        String sanitized = MessagePublishFailureSanitizer.sanitizeText(
            "amqp://user:raw-pass@rabbit:5672 failed token=raw-token secret:raw-secret "
                + "url=https://broker.example/publish?access_token=raw-access&sign=raw-sign "
                + "Authorization: Bearer raw.bearer-token"
        );

        assertThat(sanitized).contains("amqp://user:****@rabbit:5672");
        assertThat(sanitized).contains("token=****");
        assertThat(sanitized).contains("secret:****");
        assertThat(sanitized).contains("access_token=****");
        assertThat(sanitized).contains("sign=****");
        assertThat(sanitized).contains("Bearer ****");
        assertThat(sanitized)
            .doesNotContain("raw-pass", "raw-token", "raw-secret", "raw-access", "raw-sign", "raw.bearer-token");
    }

    @Test
    void fallsBackToExceptionClassWhenMessageIsBlank() {
        String sanitized = MessagePublishFailureSanitizer.sanitize(new MessagePublishException(" "));

        assertThat(sanitized).isEqualTo("MessagePublishException");
    }
}
