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
        headers.add("X-RateLimit-Limit", "5000");
        headers.add("X-RateLimit-Remaining", " 0 ");
        headers.add("X-RateLimit-Used", "5000");
        headers.add("X-RateLimit-Reset", "1763456789");
        headers.add("X-RateLimit-Resource", "core");
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
            "rateLimitLimit=5000",
            "rateLimitRemaining=0",
            "rateLimitUsed=5000",
            "rateLimitReset=1763456789",
            "rateLimitResource=core",
            "responseBody={\"token\":\"***\",\"message\":\"bad sk-***\"}"
        );
        assertThat(message).doesNotContain("raw-token", "sk-secret123456789");
    }

    @Test
    void rateLimitHeadersAreCleanedBeforeAppending() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-RateLimit-Remaining", " 12<script> ");
        headers.add("X-RateLimit-Reset", "Wed, 21 Oct 2026 07:28:00 GMT\nunsafe");
        ExternalHttpFailureDetail detail = ExternalHttpFailureDetail.from(responseException(
            403,
            "Forbidden",
            "",
            headers
        ));

        String message = detail.appendTo("Forbidden");

        assertThat(message).contains(
            "rateLimitRemaining=12script",
            "rateLimitReset=Wed, 21 Oct 2026 07:28:00 GMTunsafe"
        );
        assertThat(message).doesNotContain("<", ">");
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
    void responseBodySummaryIsSharedAfterSanitization() {
        String longBody = "{\"token\":\"raw-token\",\"message\":\"" + "a".repeat(260) + "\"}";
        ExternalHttpFailureDetail detail = ExternalHttpFailureDetail.from(responseException(
            500,
            "Internal Server Error",
            longBody,
            new HttpHeaders()
        ));

        String externalMessage = detail.appendTo("GitHub failed");
        String webhookMessage = detail.webhookMessage("Webhook HTTP request failed", 500);

        assertThat(responseBody(externalMessage)).hasSize(240).endsWith("...");
        assertThat(responseBody(webhookMessage)).hasSize(240).endsWith("...");
        assertThat(externalMessage).doesNotContain("raw-token");
        assertThat(webhookMessage).doesNotContain("raw-token");
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

    private String responseBody(String message) {
        return message.substring(message.indexOf("responseBody=") + "responseBody=".length());
    }
}
