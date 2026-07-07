package com.repoguard.agent.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;

class ExternalHttpFailureDetailTest {

    @Test
    void appendToAddsRetryAfterAndSanitizedBodyForExternalCalls() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, " 60\nunsafe ");
        ExternalHttpFailureDetail detail = ExternalHttpFailureDetail.from(responseException(
            429,
            "Too Many Requests",
            "{\"token\":\"raw-token\",\"message\":\"bad sk-secret123456789\"}",
            headers
        ));

        String message = detail.appendTo("Too Many Requests");

        assertThat(message).contains(
            "Too Many Requests",
            "retryAfter=60unsafe",
            "responseBody={\"token\":\"***\",\"message\":\"bad sk-***\"}"
        );
        assertThat(message).doesNotContain("raw-token", "sk-secret123456789");
    }

    @Test
    void webhookMessageUsesSharedRetryAfterAndWebhookSanitizer() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "30");
        ExternalHttpFailureDetail detail = ExternalHttpFailureDetail.from(responseException(
            429,
            "Too Many Requests",
            "{\"access_token\":\"raw-token\"}",
            headers
        ));

        String message = detail.webhookMessage("Webhook HTTP request failed", 429);

        assertThat(message).contains(
            "Webhook HTTP request failed status=429",
            "retryAfter=30",
            "responseBody={\"access_token\":\"****\"}"
        );
        assertThat(message).doesNotContain("raw-token");
    }

    @Test
    void fromRejectsMissingException() {
        assertThatThrownBy(() -> ExternalHttpFailureDetail.from(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("ex");
    }

    private RestClientResponseException responseException(
        int statusCode,
        String statusText,
        String responseBody,
        HttpHeaders headers
    ) {
        return new RestClientResponseException(
            statusText,
            statusCode,
            statusText,
            headers,
            responseBody.getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8
        );
    }
}
