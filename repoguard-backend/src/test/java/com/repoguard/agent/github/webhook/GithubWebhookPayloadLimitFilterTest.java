package com.repoguard.agent.github.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
            OBJECT_MAPPER
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
            OBJECT_MAPPER
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
            OBJECT_MAPPER
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
            OBJECT_MAPPER
        );
        MockHttpServletRequest request = webhookRequest("12345".getBytes(StandardCharsets.UTF_8));
        request.addHeader("X-Real-IP", "203.0.113.8");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertRejected(response, 429, "TOO_MANY_REQUESTS");
        verify(rateLimiter).rejected("ip_rate_limit");
        verifyNoInteractions(chain);
    }

    private GithubWebhookRateLimiter allowingRateLimiter() {
        GithubWebhookRateLimiter rateLimiter = mock(GithubWebhookRateLimiter.class);
        when(rateLimiter.tryAcquireIp(anyString())).thenReturn(true);
        return rateLimiter;
    }

    private GithubWebhookProperties propertiesWithLimit(int limit) {
        GithubWebhookProperties properties = new GithubWebhookProperties();
        properties.setMaxPayloadBytes(limit);
        return properties;
    }

    private MockHttpServletRequest webhookRequest(byte[] payload) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", WEBHOOK_PATH);
        request.setRemoteAddr("198.51.100.10");
        request.setContent(payload);
        return request;
    }

    private UnknownLengthRequest webhookRequestWithoutContentLength(byte[] payload) {
        UnknownLengthRequest request = new UnknownLengthRequest(payload);
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

        private UnknownLengthRequest(byte[] body) {
            super("POST", WEBHOOK_PATH);
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
