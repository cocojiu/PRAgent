package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.external.ExternalCallException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

class ReviewExecutionFailureClassifierTest {

    private final ReviewExecutionFailureClassifier classifier = new ReviewExecutionFailureClassifier();

    @Test
    void usesExternalCallCategoryWhenAvailable() {
        RuntimeException failure = new ExternalCallException(
            "github",
            "github_timeout",
            true,
            null,
            "timeout",
            new IllegalStateException("socket timeout")
        );

        assertThat(classifier.failureCategory(failure)).isEqualTo("github_timeout");
    }

    @Test
    void classifiesRestClientRateLimitByStatusCode() {
        assertThat(classifier.failureCategory(responseException(429)))
            .isEqualTo("review_external_rate_limited");
    }

    @Test
    void classifiesRestClientAuthFailureByStatusCode() {
        assertThat(classifier.failureCategory(responseException(401)))
            .isEqualTo("review_external_auth_failed");
    }

    @Test
    void classifiesRestClientServerFailureByStatusCode() {
        assertThat(classifier.failureCategory(responseException(503)))
            .isEqualTo("review_external_service_unavailable");
    }

    @Test
    void classifiesStructuredHttpFailureMessageByStatusCode() {
        RuntimeException failure = new IllegalStateException(
            "Review external request failed status=500 responseBody={\"error\":\"temporary\"}"
        );

        assertThat(classifier.failureCategory(failure))
            .isEqualTo("review_external_service_unavailable");
    }

    @Test
    void classifiesRetryAfterMessageAsRateLimited() {
        RuntimeException failure = new IllegalStateException("provider throttled retryAfter=30");

        assertThat(classifier.failureCategory(failure))
            .isEqualTo("review_external_rate_limited");
    }

    @Test
    void classifiesRateLimitDiagnosticsAsRateLimited() {
        RuntimeException failure = new IllegalStateException(
            "provider throttled rateLimitRemaining=0 rateLimitReset=1763456789 responseBody={}"
        );

        assertThat(classifier.failureCategory(failure))
            .isEqualTo("review_external_rate_limited");
    }

    @Test
    void classifiesTimeoutByCauseChain() {
        RuntimeException failure = new ResourceAccessException(
            "I/O error",
            new SocketTimeoutException("Read timed out")
        );

        assertThat(classifier.failureCategory(failure))
            .isEqualTo("review_timeout");
    }

    @Test
    void classifiesStateConflict() {
        assertThat(classifier.failureCategory(new CannotAcquireLockException("deadlock")))
            .isEqualTo("review_state_conflict");
    }

    @Test
    void classifiesDatabaseError() {
        assertThat(classifier.failureCategory(new DataAccessResourceFailureException("database unavailable")))
            .isEqualTo("review_database_error");
    }

    @Test
    void classifiesPayloadFailure() {
        assertThat(classifier.failureCategory(new IllegalArgumentException("payload json is invalid")))
            .isEqualTo("review_payload_invalid");
    }

    @Test
    void classifiesConfigurationFailure() {
        assertThat(classifier.failureCategory(new IllegalStateException("LLM API key is required")))
            .isEqualTo("review_configuration_invalid");
    }

    @Test
    void fallsBackToStableExecutionFailureCategory() {
        assertThat(classifier.failureCategory(new IllegalStateException("boom")))
            .isEqualTo("review_execution_failed");
    }

    private RestClientResponseException responseException(int statusCode) {
        return new RestClientResponseException(
            "Review external request failed",
            statusCode,
            "status",
            HttpHeaders.EMPTY,
            new byte[0],
            StandardCharsets.UTF_8
        );
    }
}
