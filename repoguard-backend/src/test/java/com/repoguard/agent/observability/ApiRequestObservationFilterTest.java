package com.repoguard.agent.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

class ApiRequestObservationFilterTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final RepoGuardMetrics metrics = RepoGuardMetrics.forTesting(
        meterRegistry,
        new com.repoguard.agent.worker.ReviewExecutionFailureClassifier()
    );
    private final ObservationPathNormalizer pathNormalizer = new ObservationPathNormalizer();
    private final ApiRequestObservationFilter filter = new ApiRequestObservationFilter(
        metrics,
        thresholdMonitor(),
        pathNormalizer
    );

    @Test
    void constructorRejectsMissingThresholdMonitor() {
        assertThatThrownBy(() -> new ApiRequestObservationFilter(metrics, null, pathNormalizer))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("thresholdMonitor");
    }

    @Test
    void constructorRejectsMissingPathNormalizer() {
        assertThatThrownBy(() -> new ApiRequestObservationFilter(metrics, thresholdMonitor(), null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("pathNormalizer");
    }

    @Test
    void recordsApiDurationStatusRouteAndResponseBytes() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reviews/521/timeline");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/reviews/{id}/timeline");
        MockHttpServletResponse response = new MockHttpServletResponse();
        byte[] body = "{\"items\":[]}".getBytes(StandardCharsets.UTF_8);

        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(
                jakarta.servlet.ServletRequest servletRequest,
                jakarta.servlet.ServletResponse servletResponse
            ) throws IOException {
                HttpServletResponse httpResponse = (HttpServletResponse) servletResponse;
                httpResponse.setStatus(200);
                httpResponse.getOutputStream().write(body);
            }
        });

        assertThat(timerCount(
            "repoguard.api.request.duration",
            "method", "GET",
            "path", "/api/v1/reviews/{id}/timeline",
            "status", "200",
            "outcome", "success"
        )).isEqualTo(1);
        assertThat(responseBytes(
            "repoguard.api.response.bytes",
            "method", "GET",
            "path", "/api/v1/reviews/{id}/timeline",
            "status", "200",
            "outcome", "success"
        )).isEqualTo(body.length);
    }

    @Test
    void usesUnmatchedPathConstantWhenHandlerPatternIsMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
            "POST",
            "/api/v1/reviews/521/github-comments/preview"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(
                jakarta.servlet.ServletRequest servletRequest,
                jakarta.servlet.ServletResponse servletResponse
            ) throws IOException {
                HttpServletResponse httpResponse = (HttpServletResponse) servletResponse;
                httpResponse.setStatus(201);
                httpResponse.getWriter().write("created");
            }
        });

        assertThat(timerCount(
            "repoguard.api.request.duration",
            "method", "POST",
            "path", "/api/v1/unmatched",
            "status", "201",
            "outcome", "success"
        )).isEqualTo(1);
        assertThat(responseBytes(
            "repoguard.api.response.bytes",
            "method", "POST",
            "path", "/api/v1/unmatched",
            "status", "201",
            "outcome", "success"
        )).isEqualTo("created".getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void collapsesClientControlledUrisIntoSingleUnmatchedSeries() throws Exception {
        for (int index = 0; index < 3; index++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/probe-" + index);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain() {
                @Override
                public void doFilter(
                    jakarta.servlet.ServletRequest servletRequest,
                    jakarta.servlet.ServletResponse servletResponse
                ) {
                    ((HttpServletResponse) servletResponse).setStatus(401);
                }
            });
        }

        assertThat(meterRegistry.find("repoguard.api.request.duration").timers()).hasSize(1);
        assertThat(timerCount(
            "repoguard.api.request.duration",
            "method", "GET",
            "path", "/api/v1/unmatched",
            "status", "401",
            "outcome", "client_error"
        )).isEqualTo(3);
    }

    @Test
    void recordsServerErrorWhenApiChainThrowsBeforeStatusIsSet() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reviews/521");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/reviews/{id}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(
                jakarta.servlet.ServletRequest servletRequest,
                jakarta.servlet.ServletResponse servletResponse
            ) throws ServletException {
                throw new ServletException("boom");
            }
        })).isInstanceOf(ServletException.class);

        assertThat(timerCount(
            "repoguard.api.request.duration",
            "method", "GET",
            "path", "/api/v1/reviews/{id}",
            "status", "500",
            "outcome", "server_error"
        )).isEqualTo(1);
    }

    @Test
    void recordsThresholdSignalWhenApiResponseBytesExceedConfiguredLimit() throws Exception {
        ObservabilityThresholdProperties properties = new ObservabilityThresholdProperties();
        properties.setApiResponseBytes(4);
        ApiRequestObservationFilter thresholdFilter = new ApiRequestObservationFilter(
            metrics,
            new ObservabilityThresholdMonitor(metrics, properties),
            pathNormalizer
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reviews/521");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/reviews/{id}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        thresholdFilter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(
                jakarta.servlet.ServletRequest servletRequest,
                jakarta.servlet.ServletResponse servletResponse
            ) throws IOException {
                HttpServletResponse httpResponse = (HttpServletResponse) servletResponse;
                httpResponse.setStatus(200);
                httpResponse.getWriter().write("large-response");
            }
        });

        assertThat(counter(
            "repoguard.observability.threshold.exceeded",
            "signal", "api_response_bytes",
            "subject", "get_api_v1_reviews_id_"
        )).isEqualTo(1.0);
    }

    @Test
    void skipsNonApiRequests() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(meterRegistry.find("repoguard.api.request.duration").timer()).isNull();
    }

    private long timerCount(String name, String... tags) {
        return meterRegistry.find(name).tags(tags).timer().count();
    }

    private double responseBytes(String name, String... tags) {
        return meterRegistry.find(name).tags(tags).summary().totalAmount();
    }

    private double counter(String name, String... tags) {
        return meterRegistry.find(name).tags(tags).counter().count();
    }

    private ObservabilityThresholdMonitor thresholdMonitor() {
        return new ObservabilityThresholdMonitor(metrics, new ObservabilityThresholdProperties());
    }
}
