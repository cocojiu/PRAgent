package com.repoguard.agent.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClientResponseException;

class ExternalHttpResponseReaderTest {

    private final ExternalHttpResponseReader reader = new ExternalHttpResponseReader();

    @Test
    void readSuccessfulBodyReturnsRawBody() throws IOException {
        MockClientHttpResponse response = response(HttpStatus.OK, "{\"choices\":[]}");

        byte[] body = reader.readSuccessfulBody(
            response,
            "LLM request failed",
            ExternalHttpResponseProfile.LLM
        );

        assertThat(new String(body, StandardCharsets.UTF_8)).isEqualTo("{\"choices\":[]}");
    }

    @Test
    void readSuccessfulBodyPreservesErrorStatusHeadersAndBody() {
        MockClientHttpResponse response = response(
            HttpStatus.TOO_MANY_REQUESTS,
            "{\"error\":{\"message\":\"rate limited\"}}"
        );
        response.getHeaders().add("Retry-After", "30");

        assertThatThrownBy(() -> reader.readSuccessfulBody(
            response,
            "External request failed",
            ExternalHttpResponseProfile.CONNECTION_PROBE
        ))
            .isInstanceOfSatisfying(RestClientResponseException.class, ex -> {
                assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
                assertThat(ex.getStatusText()).isEqualTo("Too Many Requests");
                assertThat(ex.getResponseHeaders()).isNotNull();
                assertThat(ex.getResponseHeaders().getFirst("Retry-After")).isEqualTo("30");
                assertThat(ex.getResponseBodyAsString()).isEqualTo("{\"error\":{\"message\":\"rate limited\"}}");
                assertThat(ex.getMessage()).contains("External request failed with HTTP status 429");
            });
    }

    @Test
    void readSuccessfulBodyRejectsMissingResponse() {
        assertThatThrownBy(() -> reader.readSuccessfulBody(
            null,
            "External request failed",
            ExternalHttpResponseProfile.CONNECTION_PROBE
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("response");
    }

    @Test
    void readSuccessfulBodyRejectsMissingFailureMessagePrefix() {
        MockClientHttpResponse response = response(HttpStatus.INTERNAL_SERVER_ERROR, "server error");

        assertThatThrownBy(() -> reader.readSuccessfulBody(
            response,
            null,
            ExternalHttpResponseProfile.CONNECTION_PROBE
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("failureMessagePrefix");
    }

    @Test
    void readSuccessfulBodyRejectsMissingProfile() {
        MockClientHttpResponse response = response(HttpStatus.OK, "{}");

        assertThatThrownBy(() -> reader.readSuccessfulBody(response, "External request failed", null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("profile");
    }

    @Test
    void rejectsDeclaredOversizedResponseBeforeReadingAndRecordsMetric() throws IOException {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ExternalHttpResponseReader meteredReader = new ExternalHttpResponseReader(registry);
        ClientHttpResponse response = mock(ClientHttpResponse.class);
        HttpHeaders headers = new HttpHeaders();
        long declaredBytes = ExternalHttpResponseProfile.NOTIFICATION.maxBytes() + 1L;
        headers.setContentLength(declaredBytes);
        when(response.getHeaders()).thenReturn(headers);

        assertThatThrownBy(() -> meteredReader.readSuccessfulBody(
            response,
            "Webhook request failed",
            ExternalHttpResponseProfile.NOTIFICATION
        )).isInstanceOfSatisfying(ExternalHttpResponseTooLargeException.class, ex -> {
            assertThat(ex.getProfile()).isEqualTo(ExternalHttpResponseProfile.NOTIFICATION);
            assertThat(ex.getMaxBytes()).isEqualTo(ExternalHttpResponseProfile.NOTIFICATION.maxBytes());
            assertThat(ex.getObservedBytes()).isEqualTo(declaredBytes);
            assertThat(ex.getDetection()).isEqualTo("content_length");
            assertThat(ex.getMessage()).doesNotContain("responseBody");
        });

        verify(response).close();
        verify(response, never()).getBody();
        assertThat(registry.counter(
            "repoguard.external.response.too_large",
            "profile", "notification",
            "detection", "content_length"
        ).count()).isEqualTo(1.0);
    }

    @Test
    void rejectsChunkedOversizedResponseAfterReadingOnlyLimitPlusOne() throws IOException {
        ClientHttpResponse response = mock(ClientHttpResponse.class);
        HttpHeaders headers = new HttpHeaders();
        int maxBytes = ExternalHttpResponseProfile.CONNECTION_PROBE.maxBytes();
        when(response.getHeaders()).thenReturn(headers);
        when(response.getBody()).thenReturn(new ByteArrayInputStream(new byte[maxBytes + 1]));

        assertThatThrownBy(() -> reader.readSuccessfulBody(
            response,
            "Connection probe failed",
            ExternalHttpResponseProfile.CONNECTION_PROBE
        )).isInstanceOfSatisfying(ExternalHttpResponseTooLargeException.class, ex -> {
            assertThat(ex.getObservedBytes()).isEqualTo(maxBytes + 1L);
            assertThat(ex.getDetection()).isEqualTo("stream");
        });

        verify(response).close();
    }

    private MockClientHttpResponse response(HttpStatus status, String body) {
        return new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), status);
    }
}
