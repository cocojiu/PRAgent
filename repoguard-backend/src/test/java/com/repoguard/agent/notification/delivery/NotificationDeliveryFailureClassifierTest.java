package com.repoguard.agent.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

class NotificationDeliveryFailureClassifierTest {

    private final NotificationDeliveryFailureClassifier classifier = new NotificationDeliveryFailureClassifier();

    @Test
    void classifiesRestClientRateLimitByStatusCode() {
        assertThat(classifier.failureCategory(responseException(429)))
            .isEqualTo("notification_http_rate_limited");
    }

    @Test
    void classifiesRestClientAuthFailureByStatusCode() {
        assertThat(classifier.failureCategory(responseException(403)))
            .isEqualTo("notification_http_auth_failed");
    }

    @Test
    void classifiesRestClientServerFailureByStatusCode() {
        assertThat(classifier.failureCategory(responseException(503)))
            .isEqualTo("notification_http_service_unavailable");
    }

    @Test
    void classifiesWebhookHttpFailureMessageByStatusCode() {
        RuntimeException failure = new IllegalStateException(
            "Webhook HTTP request failed status=429 retryAfter=60 responseBody={\"errcode\":1}"
        );

        assertThat(classifier.failureCategory(failure))
            .isEqualTo("notification_http_rate_limited");
    }

    @Test
    void classifiesRateLimitDiagnosticsAsRateLimited() {
        RuntimeException failure = new IllegalStateException(
            "Webhook HTTP request failed rateLimitRemaining=0 rateLimitReset=1763456789 responseBody={}"
        );

        assertThat(classifier.failureCategory(failure))
            .isEqualTo("notification_http_rate_limited");
    }

    @Test
    void classifiesTimeoutByCauseChain() {
        RuntimeException failure = new ResourceAccessException(
            "I/O error",
            new SocketTimeoutException("Read timed out")
        );

        assertThat(classifier.failureCategory(failure))
            .isEqualTo("notification_timeout");
    }

    @Test
    void classifiesChannelConfigFailure() {
        assertThat(classifier.failureCategory(new IllegalStateException("Webhook credentials cannot be decrypted")))
            .isEqualTo("notification_channel_config_invalid");
    }

    @Test
    void classifiesPayloadFailure() {
        assertThat(classifier.failureCategory(new IllegalArgumentException("Notification payload json is invalid")))
            .isEqualTo("notification_payload_invalid");
    }

    @Test
    void fallsBackToStableDeliveryFailureCategory() {
        assertThat(classifier.failureCategory(new IllegalStateException("boom")))
            .isEqualTo("notification_delivery_failed");
    }

    private RestClientResponseException responseException(int statusCode) {
        return new RestClientResponseException(
            "Webhook HTTP request failed",
            statusCode,
            "status",
            HttpHeaders.EMPTY,
            new byte[0],
            StandardCharsets.UTF_8
        );
    }
}
