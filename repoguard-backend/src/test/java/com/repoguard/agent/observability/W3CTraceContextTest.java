package com.repoguard.agent.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;

class W3CTraceContextTest {

    @Test
    void parsesValidHeaderAndCreatesChildWithSameTraceId() {
        W3CTraceContext parent = W3CTraceContext.parse(
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
        ).orElseThrow();

        W3CTraceContext child = parent.child();

        assertThat(child.traceId()).isEqualTo(parent.traceId());
        assertThat(child.spanId()).isNotEqualTo(parent.spanId());
        assertThat(child.traceparent()).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
    }

    @Test
    void rejectsMalformedOrAllZeroHeaders() {
        assertThat(W3CTraceContext.parse("01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"))
            .isEmpty();
        assertThat(W3CTraceContext.parse("00-00000000000000000000000000000000-00f067aa0ba902b7-01"))
            .isEmpty();
        assertThat(W3CTraceContext.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01"))
            .isEmpty();
    }

    @Test
    void propagatesOnlyTraceparentAcrossHttpAndRabbitAndRestoresThreadState() throws Exception {
        W3CTraceContext context = W3CTraceContext.root();
        try (TracePropagation.Scope _ = TracePropagation.withContext(context)) {
            MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("https://example.invalid"));
            ClientHttpResponse response = new W3CTracePropagationInterceptor().intercept(
                request,
                new byte[0],
                (capturedRequest, body) -> null
            );
            MessageProperties properties = new MessageProperties();
            TracePropagation.inject(properties);

            assertThat(response).isNull();
            assertThat(request.getHeaders().getFirst(TracePropagation.TRACEPARENT_HEADER))
                .isEqualTo(context.traceparent());
            assertThat((String) properties.getHeader(TracePropagation.TRACEPARENT_HEADER))
                .isEqualTo(context.traceparent());
            assertThat(properties.getHeaders().keySet()).doesNotContain("authorization");
        }
        assertThat(TracePropagation.current()).isEmpty();
    }

    @Test
    void allowsOnlyLowCardinalityTraceAttributes() {
        assertThat(TracePropagation.safeAttributes(Map.of(
            "operation", "review",
            "provider", "github",
            "token", "must-not-leak",
            "code", "source must not become an attribute"
        ))).containsOnlyKeys("operation", "provider");
    }
}
