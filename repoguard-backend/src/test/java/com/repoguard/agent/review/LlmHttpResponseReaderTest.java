package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClientResponseException;

class LlmHttpResponseReaderTest {

    private final LlmHttpResponseReader reader = new LlmHttpResponseReader();

    @Test
    void readSuccessfulBodyReturnsRawBody() throws IOException {
        MockClientHttpResponse response = response(HttpStatus.OK, "{\"choices\":[]}");

        byte[] body = reader.readSuccessfulBody(response, "LLM request failed");

        assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo("{\"choices\":[]}");
    }

    @Test
    void readSuccessfulBodyPreservesErrorStatusHeadersAndBody() {
        MockClientHttpResponse response = response(
            HttpStatus.TOO_MANY_REQUESTS,
            "{\"error\":{\"message\":\"rate limited\"}}"
        );
        response.getHeaders().add("Retry-After", "30");

        assertThatThrownBy(() -> reader.readSuccessfulBody(response, "LLM request failed"))
            .isInstanceOfSatisfying(RestClientResponseException.class, ex -> {
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                assertThat(ex.getStatusText()).isEqualTo("Too Many Requests");
                assertThat(ex.getResponseHeaders()).isNotNull();
                assertThat(ex.getResponseHeaders().getFirst("Retry-After")).isEqualTo("30");
                assertThat(ex.getResponseBodyAsString()).isEqualTo("{\"error\":{\"message\":\"rate limited\"}}");
                assertThat(ex.getMessage()).contains("LLM request failed with HTTP status 429");
            });
    }

    @Test
    void readSuccessfulBodyRejectsMissingResponse() {
        assertThatThrownBy(() -> reader.readSuccessfulBody(null, "LLM request failed"))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("response");
    }

    @Test
    void readSuccessfulBodyRejectsMissingFailureMessagePrefix() {
        MockClientHttpResponse response = response(HttpStatus.INTERNAL_SERVER_ERROR, "server error");

        assertThatThrownBy(() -> reader.readSuccessfulBody(response, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("failureMessagePrefix");
    }

    private MockClientHttpResponse response(HttpStatus status, String body) {
        return new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), status);
    }
}
