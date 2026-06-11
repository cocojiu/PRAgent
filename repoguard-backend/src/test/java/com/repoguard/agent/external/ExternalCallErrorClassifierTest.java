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
        return new RestClientResponseException(
            statusText,
            statusCode,
            statusText,
            HttpHeaders.EMPTY,
            new byte[0],
            null
        );
    }
}
