package com.repoguard.agent.github.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.repoguard.agent.common.TrustedProxyClientIpResolver;
import com.repoguard.agent.common.TrustedProxyProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class GithubWebhookPayloadLimitFilterTest {

    private static final String WEBHOOK_PATH = "/api/v1/github/webhooks";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void rejectsDeclaredContentLengthAboveLimitWithoutInvokingChain() throws Exception {
        GithubWebhookProperties properties = propertiesWithLimit(4);
        GithubWebhookRateLimiter rateLimiter = allowingRateLimiter();
        GithubWebhookPayloadLimitFilter filter = new GithubWebhookPayloadLimitFilter(
            properties,
            rateLimiter,
            OBJECT_MAPPER,
            clientIpResolver()
        );
        MockHttpServletRequest request = webhookRequest("12345".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertRejected(response, 413, "PAYLOAD_TOO_LARGE");
        verify(rateLimiter).rejected("content_length");
        verifyNoInteractions(chain);
    }

    @Test
    void rejectsUnknownLengthStreamAsSoonAsLimitIsExceeded() throws Exception {
        GithubWebhookProperties properties = propertiesWithLimit(4);
        GithubWebhookRateLimiter rateLimiter = allowingRateLimiter();
        GithubWebhookPayloadLimitFilter filter = new GithubWebhookPayloadLimitFilter(
            properties,
            rateLimiter,
            OBJECT_MAPPER,
            clientIpResolver()
        );
        UnknownLengthRequest request = webhookRequestWithoutContentLength(
            "1234567890".getBytes(StandardCharsets.UTF_8)
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertRejected(response, 413, "PAYLOAD_TOO_LARGE");
        assertThat(request.bytesRead()).isEqualTo(5);
        verify(rateLimiter).rejected("stream_limit");
        verifyNoInteractions(chain);
    }

    @Test
    void forwardsUnknownLengthPayloadAtExactLimitUsingCachedBody() throws Exception {
        GithubWebhookProperties properties = propertiesWithLimit(4);
        GithubWebhookRateLimiter rateLimiter = allowingRateLimiter();
        GithubWebhookPayloadLimitFilter filter = new GithubWebhookPayloadLimitFilter(
            properties,
            rateLimiter,
            OBJECT_MAPPER,
            clientIpResolver()
        );
        byte[] payload = "1234".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = webhookRequestWithoutContentLength(payload);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        ArgumentCaptor<ServletRequest> requestCaptor = ArgumentCaptor.forClass(ServletRequest.class);
        verify(chain).doFilter(requestCaptor.capture(), org.mockito.ArgumentMatchers.same(response));
        HttpServletRequest cachedRequest = (HttpServletRequest) requestCaptor.getValue();
        assertThat(cachedRequest.getContentLengthLong()).isEqualTo(payload.length);
        assertThat(cachedRequest.getInputStream().readAllBytes()).containsExactly(payload);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsRateLimitedIpWith429BeforeReadingPayload() throws Exception {
        GithubWebhookProperties properties = propertiesWithLimit(4);
        GithubWebhookRateLimiter rateLimiter = mock(GithubWebhookRateLimiter.class);
        when(rateLimiter.tryAcquireIp("203.0.113.8")).thenReturn(false);
        GithubWebhookPayloadLimitFilter filter = new GithubWebhookPayloadLimitFilter(
            properties,
            rateLimiter,
            OBJECT_MAPPER,
            clientIpResolver()
        );
        MockHttpServletRequest request = webhookRequest("12345".getBytes(StandardCharsets.UTF_8));
        request.setRemoteAddr("172.18.0.2");
        request.addHeader("X-Real-IP", "203.0.113.8");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertRejected(response, 429, "TOO_MANY_REQUESTS");
        verify(rateLimiter).tryAcquireIp("203.0.113.8");
        verify(rateLimiter).rejected("ip_rate_limit");
        verifyNoInteractions(chain);
    }

    @Test
    void ignoresSpoofedForwardedIpFromUntrustedPeerWhenRateLimiting() throws Exception {
        GithubWebhookProperties properties = propertiesWithLimit(4);
        GithubWebhookRateLimiter rateLimiter = allowingRateLimiter();
        GithubWebhookPayloadLimitFilter filter = new GithubWebhookPayloadLimitFilter(
            properties,
            rateLimiter,
            OBJECT_MAPPER,
            clientIpResolver()
        );
        byte[] payload = "1234".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = webhookRequest(payload);
        request.addHeader("X-Real-IP", "203.0.113.99");
        request.addHeader("X-Forwarded-For", "203.0.113.99");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(rateLimiter).tryAcquireIp("198.51.100.10");
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void bucketsMalformedForwardedIpUnderThePeerAddress() throws Exception {
        GithubWebhookProperties properties = propertiesWithLimit(4);
        GithubWebhookRateLimiter rateLimiter = allowingRateLimiter();
        GithubWebhookPayloadLimitFilter filter = new GithubWebhookPayloadLimitFilter(
            properties,
            rateLimiter,
            OBJECT_MAPPER,
            clientIpResolver()
        );
        byte[] payload = "1234".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = webhookRequest(payload);
        request.setRemoteAddr("172.18.0.2");
        request.addHeader("X-Real-IP", "x".repeat(2048));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(rateLimiter).tryAcquireIp("172.18.0.2");
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void pathParameterVariantEntersFilterAndCountsIpRateLimit() throws Exception {
        GithubWebhookProperties properties = propertiesWithLimit(4);
        GithubWebhookRateLimiter rateLimiter = mock(GithubWebhookRateLimiter.class);
        when(rateLimiter.tryAcquireIp("198.51.100.10")).thenReturn(false);
        GithubWebhookPayloadLimitFilter filter = new GithubWebhookPayloadLimitFilter(
            properties,
            rateLimiter,
            OBJECT_MAPPER,
            clientIpResolver()
        );
        MockHttpServletRequest request = webhookRequest(WEBHOOK_PATH + ";x=1", "1234".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertRejected(response, 429, "TOO_MANY_REQUESTS");
        verify(rateLimiter).tryAcquireIp("198.51.100.10");
        verify(rateLimiter).rejected("ip_rate_limit");
        verifyNoInteractions(chain);
    }

    @Test
    void percentEncodedVariantEntersFilterAndTruncatesOversizedStream() throws Exception {
        GithubWebhookProperties properties = propertiesWithLimit(4);
        GithubWebhookRateLimiter rateLimiter = allowingRateLimiter();
        GithubWebhookPayloadLimitFilter filter = new GithubWebhookPayloadLimitFilter(
            properties,
            rateLimiter,
            OBJECT_MAPPER,
            clientIpResolver()
        );
        UnknownLengthRequest request = webhookRequestWithoutContentLength(
            "/api/v1/github/%77ebhooks",
            "1234567890".getBytes(StandardCharsets.UTF_8)
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertRejected(response, 413, "PAYLOAD_TOO_LARGE");
        assertThat(request.bytesRead()).isEqualTo(5);
        verify(rateLimiter).tryAcquireIp("198.51.100.10");
        verify(rateLimiter).rejected("stream_limit");
        verifyNoInteractions(chain);
    }

    @Test
    void pathParameterWithQueryVariantEntersFilterAndRejectsOversizedPayload() throws Exception {
        GithubWebhookProperties properties = propertiesWithLimit(4);
        GithubWebhookRateLimiter rateLimiter = allowingRateLimiter();
        GithubWebhookPayloadLimitFilter filter = new GithubWebhookPayloadLimitFilter(
            properties,
            rateLimiter,
            OBJECT_MAPPER,
            clientIpResolver()
        );
        MockHttpServletRequest request = webhookRequest(WEBHOOK_PATH + ";x=1?y=2", "12345".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertRejected(response, 413, "PAYLOAD_TOO_LARGE");
        verify(rateLimiter).tryAcquireIp("198.51.100.10");
        verify(rateLimiter).rejected("content_length");
        verifyNoInteractions(chain);
    }

    @Test
    void duplicateSlashVariantEntersFilterAndRejectsOversizedPayload() throws Exception {
        GithubWebhookProperties properties = propertiesWithLimit(4);
        GithubWebhookRateLimiter rateLimiter = allowingRateLimiter();
        GithubWebhookPayloadLimitFilter filter = new GithubWebhookPayloadLimitFilter(
            properties,
            rateLimiter,
            OBJECT_MAPPER,
            clientIpResolver()
        );
        MockHttpServletRequest request = webhookRequest("/api/v1/github//webhooks", "12345".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertRejected(response, 413, "PAYLOAD_TOO_LARGE");
        verify(rateLimiter).tryAcquireIp("198.51.100.10");
        verify(rateLimiter).rejected("content_length");
        verifyNoInteractions(chain);
    }

    @Test
    void malformedEncodedPathRejectsRequestInsteadOfSkippingFilter() {
        GithubWebhookProperties properties = propertiesWithLimit(4);
        GithubWebhookRateLimiter rateLimiter = mock(GithubWebhookRateLimiter.class);
        GithubWebhookPayloadLimitFilter filter = new GithubWebhookPayloadLimitFilter(
            properties,
            rateLimiter,
            OBJECT_MAPPER,
            clientIpResolver()
        );
        MockHttpServletRequest request = webhookRequest("/api/v1/github/%zzebhooks", "1234".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(chain, rateLimiter);
    }

    @Test
    void nonWebhookPathSkipsFilter() throws Exception {
        GithubWebhookProperties properties = propertiesWithLimit(4);
        GithubWebhookRateLimiter rateLimiter = mock(GithubWebhookRateLimiter.class);
        GithubWebhookPayloadLimitFilter filter = new GithubWebhookPayloadLimitFilter(
            properties,
            rateLimiter,
            OBJECT_MAPPER,
            clientIpResolver()
        );
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reviews");
        request.setRemoteAddr("198.51.100.10");
        request.setContent("1234567890".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(rateLimiter);
    }

    @Test
    void nonPostMethodSkipsFilter() throws Exception {
        GithubWebhookProperties properties = propertiesWithLimit(4);
        GithubWebhookRateLimiter rateLimiter = mock(GithubWebhookRateLimiter.class);
        GithubWebhookPayloadLimitFilter filter = new GithubWebhookPayloadLimitFilter(
            properties,
            rateLimiter,
            OBJECT_MAPPER,
            clientIpResolver()
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", WEBHOOK_PATH);
        request.setRemoteAddr("198.51.100.10");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(rateLimiter);
    }

    private GithubWebhookRateLimiter allowingRateLimiter() {
        GithubWebhookRateLimiter rateLimiter = mock(GithubWebhookRateLimiter.class);
        when(rateLimiter.tryAcquireIp(anyString())).thenReturn(true);
        return rateLimiter;
    }

    private TrustedProxyClientIpResolver clientIpResolver() {
        return new TrustedProxyClientIpResolver(new TrustedProxyProperties(), new SimpleMeterRegistry());
    }

    private GithubWebhookProperties propertiesWithLimit(int limit) {
        GithubWebhookProperties properties = new GithubWebhookProperties();
        properties.setMaxPayloadBytes(limit);
        return properties;
    }

    private MockHttpServletRequest webhookRequest(byte[] payload) {
        return webhookRequest(WEBHOOK_PATH, payload);
    }

    private MockHttpServletRequest webhookRequest(String requestUri, byte[] payload) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", requestUri);
        request.setRemoteAddr("198.51.100.10");
        request.setContent(payload);
        return request;
    }

    private UnknownLengthRequest webhookRequestWithoutContentLength(byte[] payload) {
        return webhookRequestWithoutContentLength(WEBHOOK_PATH, payload);
    }

    private UnknownLengthRequest webhookRequestWithoutContentLength(String requestUri, byte[] payload) {
        UnknownLengthRequest request = new UnknownLengthRequest(requestUri, payload);
        request.setRemoteAddr("198.51.100.10");
        return request;
    }

    private void assertRejected(MockHttpServletResponse response, int status, String code) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(OBJECT_MAPPER.readTree(response.getContentAsByteArray()).path("code").asText()).isEqualTo(code);
    }

    private static final class UnknownLengthRequest extends MockHttpServletRequest {
        private final byte[] body;
        private int bytesRead;

        private UnknownLengthRequest(String requestUri, byte[] body) {
            super("POST", requestUri);
            this.body = body;
        }

        @Override
        public int getContentLength() {
            return -1;
        }

        @Override
        public long getContentLengthLong() {
            return -1;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    if (readListener == null) {
                        throw new IllegalArgumentException("readListener is required");
                    }
                }

                @Override
                public int read() {
                    int value = input.read();
                    if (value >= 0) {
                        bytesRead++;
                    }
                    return value;
                }

                @Override
                public int read(byte[] bytes, int offset, int length) {
                    int count = input.read(bytes, offset, length);
                    if (count > 0) {
                        bytesRead += count;
                    }
                    return count;
                }
            };
        }

        private int bytesRead() {
            return bytesRead;
        }
    }
}
