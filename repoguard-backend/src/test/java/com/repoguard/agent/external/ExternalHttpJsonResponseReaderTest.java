package com.repoguard.agent.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClientResponseException;

class ExternalHttpJsonResponseReaderTest {

    private final ExternalHttpJsonResponseReader reader = new ExternalHttpJsonResponseReader(
        new ObjectMapper(),
        new ExternalHttpResponseReader()
    );

    @Test
    void readSuccessfulJsonParsesObjectBody() throws IOException {
        MockClientHttpResponse response = response(HttpStatus.OK, "{\"name\":\"octocat\"}");

        SampleResponse result = reader.readSuccessfulJson(
            response,
            SampleResponse.class,
            "GitHub request failed",
            ExternalHttpResponseProfile.GITHUB
        );

        assertThat(result.name()).isEqualTo("octocat");
    }

    @Test
    void readSuccessfulJsonReturnsNullForEmptyBody() throws IOException {
        MockClientHttpResponse response = response(HttpStatus.NO_CONTENT, "");

        SampleResponse result = reader.readSuccessfulJson(
            response,
            SampleResponse.class,
            "GitHub request failed",
            ExternalHttpResponseProfile.GITHUB
        );

        assertThat(result).isNull();
    }

    @Test
    void readSuccessfulTreeParsesJsonNodeBody() throws IOException {
        MockClientHttpResponse response = response(HttpStatus.OK, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");

        JsonNode result = reader.readSuccessfulTree(
            response,
            "LLM request failed",
            ExternalHttpResponseProfile.LLM
        );

        assertThat(result.at("/choices/0/message/content").asText()).isEqualTo("ok");
    }

    @Test
    void readSuccessfulTreeReturnsNullForEmptyBody() throws IOException {
        MockClientHttpResponse response = response(HttpStatus.NO_CONTENT, "");

        JsonNode result = reader.readSuccessfulTree(
            response,
            "LLM request failed",
            ExternalHttpResponseProfile.LLM
        );

        assertThat(result).isNull();
    }

    @Test
    void readSuccessfulJsonPreservesHttpFailureContext() {
        MockClientHttpResponse response = response(HttpStatus.TOO_MANY_REQUESTS, "{\"message\":\"rate limited\"}");
        response.getHeaders().add("Retry-After", "30");

        assertThatThrownBy(() -> reader.readSuccessfulJson(
            response,
            SampleResponse.class,
            "GitHub request failed",
            ExternalHttpResponseProfile.GITHUB
        ))
            .isInstanceOfSatisfying(RestClientResponseException.class, ex -> {
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                assertThat(ex.getResponseHeaders()).isNotNull();
                assertThat(ex.getResponseHeaders().getFirst("Retry-After")).isEqualTo("30");
                assertThat(ex.getResponseBodyAsString()).isEqualTo("{\"message\":\"rate limited\"}");
            });
    }

    @Test
    void readSuccessfulTreePreservesHttpFailureContext() {
        MockClientHttpResponse response = response(HttpStatus.UNAUTHORIZED, "{\"error\":\"bad token\"}");
        response.getHeaders().add("X-Request-Id", "req-123");

        assertThatThrownBy(() -> reader.readSuccessfulTree(
            response,
            "LLM request failed",
            ExternalHttpResponseProfile.LLM
        ))
            .isInstanceOfSatisfying(RestClientResponseException.class, ex -> {
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(ex.getResponseHeaders()).isNotNull();
                assertThat(ex.getResponseHeaders().getFirst("X-Request-Id")).isEqualTo("req-123");
                assertThat(ex.getResponseBodyAsString()).isEqualTo("{\"error\":\"bad token\"}");
            });
    }

    @Test
    void readSuccessfulJsonRejectsMissingResponseType() {
        MockClientHttpResponse response = response(HttpStatus.OK, "{}");

        assertThatThrownBy(() -> reader.readSuccessfulJson(
            response,
            null,
            "GitHub request failed",
            ExternalHttpResponseProfile.GITHUB
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("responseType");
    }

    private MockClientHttpResponse response(HttpStatus status, String body) {
        return new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), status);
    }

    private record SampleResponse(String name) {
    }
}
