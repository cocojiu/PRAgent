package com.repoguard.agent.external;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

class ExternalCallErrorClassifierTest {

    @Test
    void classifiesGithubAuthenticationAndPermissionFailuresAsNonRetryable() {
        ExternalCallException unauthorized = ExternalCallErrorClassifier.github(responseException(401, "Bad credentials"));
        ExternalCallException forbidden = ExternalCallErrorClassifier.github(responseException(403, "Resource not accessible by integration"));

        assertThat(unauthorized.getCategory()).isEqualTo("github_token_invalid");
        assertThat(unauthorized.isRetryable()).isFalse();
        assertThat(unauthorized.getStatusCode()).isEqualTo(401);
        assertThat(forbidden.getCategory()).isEqualTo("github_permission_denied");
        assertThat(forbidden.isRetryable()).isFalse();
    }

    @Test
    void classifiesGithubRateLimitAndServerFailuresAsRetryable() {
        ExternalCallException rateLimited = ExternalCallErrorClassifier.github(responseException(429, "API rate limit exceeded"));
        ExternalCallException unavailable = ExternalCallErrorClassifier.github(responseException(502, "Bad gateway"));

        assertThat(rateLimited.getCategory()).isEqualTo("github_rate_limited");
        assertThat(rateLimited.isRetryable()).isTrue();
        assertThat(unavailable.getCategory()).isEqualTo("github_service_unavailable");
        assertThat(unavailable.isRetryable()).isTrue();
    }

    @Test
    void includesSafeRetryAfterHeaderForGithubRateLimit() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "60");

        ExternalCallException rateLimited = ExternalCallErrorClassifier.github(responseException(
            429,
            "API rate limit exceeded",
            "{\"message\":\"API rate limit exceeded\",\"token\":\"raw-token-value\"}",
            headers
        ));

        assertThat(rateLimited.getCategory()).isEqualTo("github_rate_limited");
        assertThat(rateLimited.isRetryable()).isTrue();
        assertThat(rateLimited.getMessage()).contains(
            "status=429",
            "retryAfter=60",
            "responseBody={\"message\":\"API rate limit exceeded\",\"token\":\"***\"}"
        );
        assertThat(rateLimited.getMessage()).doesNotContain("raw-token-value");
    }

    @Test
    void classifiesLlmHttpStatusFailuresWithRetrySemantics() {
        ExternalCallException unauthorized = ExternalCallErrorClassifier.llm(responseException(401, "Unauthorized"));
        ExternalCallException forbidden = ExternalCallErrorClassifier.llm(responseException(403, "Forbidden"));
        ExternalCallException rateLimited = ExternalCallErrorClassifier.llm(responseException(429, "Too Many Requests"));
        ExternalCallException unavailable = ExternalCallErrorClassifier.llm(responseException(503, "Service Unavailable"));
        ExternalCallException invalid = ExternalCallErrorClassifier.llm(responseException(422, "Invalid request"));
        ExternalCallException notFound = ExternalCallErrorClassifier.llm(responseException(404, "model not found"));

        assertThat(unauthorized.getCategory()).isEqualTo("llm_auth_failed");
        assertThat(unauthorized.isRetryable()).isFalse();
        assertThat(unauthorized.getStatusCode()).isEqualTo(401);
        assertThat(forbidden.getCategory()).isEqualTo("llm_auth_failed");
        assertThat(forbidden.isRetryable()).isFalse();
        assertThat(rateLimited.getCategory()).isEqualTo("llm_rate_limited");
        assertThat(rateLimited.isRetryable()).isTrue();
        assertThat(unavailable.getCategory()).isEqualTo("llm_service_unavailable");
        assertThat(unavailable.isRetryable()).isTrue();
        assertThat(invalid.getCategory()).isEqualTo("llm_request_invalid");
        assertThat(invalid.isRetryable()).isFalse();
        assertThat(notFound.getCategory()).isEqualTo("llm_model_or_endpoint_not_found");
        assertThat(notFound.isRetryable()).isFalse();
    }

    @Test
    void includesSanitizedLlmHttpResponseBodySummary() {
        ExternalCallException unauthorized = ExternalCallErrorClassifier.llm(responseException(
            401,
            "Unauthorized",
            "{\"error\":\"invalid api_key sk-secret123456789\",\"token\":\"Bearer raw-token-value\"}"
        ));
        ExternalCallException unavailable = ExternalCallErrorClassifier.llm(responseException(
            500,
            "Internal Server Error",
            "<html>upstream failed</html>"
        ));

        assertThat(unauthorized.getCategory()).isEqualTo("llm_auth_failed");
        assertThat(unauthorized.getMessage()).contains("responseBody=", "invalid api_key sk-***", "Bearer ***");
        assertThat(unauthorized.getMessage()).doesNotContain("sk-secret123456789", "raw-token-value");
        assertThat(unavailable.getCategory()).isEqualTo("llm_service_unavailable");
        assertThat(unavailable.isRetryable()).isTrue();
        assertThat(unavailable.getMessage()).contains("status=500", "responseBody=<html>upstream failed</html>");
    }

    @Test
    void includesSafeRetryAfterHeaderForLlmRateLimit() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "120");

        ExternalCallException rateLimited = ExternalCallErrorClassifier.llm(responseException(
            429,
            "Too Many Requests",
            "{\"error\":\"rate limited\",\"token\":\"raw-token-value\"}",
            headers
        ));

        assertThat(rateLimited.getCategory()).isEqualTo("llm_rate_limited");
        assertThat(rateLimited.isRetryable()).isTrue();
        assertThat(rateLimited.getMessage()).contains(
            "status=429",
            "retryAfter=120",
            "responseBody={\"error\":\"rate limited\",\"token\":\"***\"}"
        );
        assertThat(rateLimited.getMessage()).doesNotContain("raw-token-value");
    }

    @Test
    void truncatesLongHttpResponseBodyDetails() {
        ExternalCallException classified = ExternalCallErrorClassifier.llm(responseException(
            500,
            "Internal Server Error",
            "x".repeat(400)
        ));

        assertThat(classified.getMessage()).contains("...");
        assertThat(classified.getMessage().length()).isLessThan(430);
    }

    @Test
    void classifiesTimeoutWithoutHttpStatusAsRetryable() {
        ResourceAccessException timeout = new ResourceAccessException(
            "Read timed out",
            new SocketTimeoutException("Read timed out")
        );

        ExternalCallException classified = ExternalCallErrorClassifier.llm(timeout);

        assertThat(classified.getCategory()).isEqualTo("llm_timeout");
        assertThat(classified.isRetryable()).isTrue();
        assertThat(classified.getStatusCode()).isNull();
    }

    @Test
    void classifiesGithubTimeoutWithoutHttpStatusAsRetryable() {
        ResourceAccessException timeout = new ResourceAccessException("connect timed out");

        ExternalCallException classified = ExternalCallErrorClassifier.github(timeout);

        assertThat(classified.getCategory()).isEqualTo("github_timeout");
        assertThat(classified.isRetryable()).isTrue();
        assertThat(classified.getStatusCode()).isNull();
    }

    @Test
    void keepsExistingExternalCallExceptionUnchanged() {
        ExternalCallException original = new ExternalCallException(
            "GitHub",
            "github_rate_limited",
            true,
            429,
            "rate limited",
            null
        );

        assertThat(ExternalCallErrorClassifier.github(original)).isSameAs(original);
    }

    private RestClientResponseException responseException(int statusCode, String statusText) {
        return responseException(statusCode, statusText, "");
    }

    private RestClientResponseException responseException(int statusCode, String statusText, String responseBody) {
        return responseException(statusCode, statusText, responseBody, HttpHeaders.EMPTY);
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
            responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8),
            null
        );
    }
}
