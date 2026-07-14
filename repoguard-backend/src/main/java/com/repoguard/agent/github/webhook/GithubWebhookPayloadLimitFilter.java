package com.repoguard.agent.github.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class GithubWebhookPayloadLimitFilter extends OncePerRequestFilter {

    private static final String WEBHOOK_PATH = "/api/v1/github/webhooks";

    private final GithubWebhookProperties properties;
    private final GithubWebhookRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public GithubWebhookPayloadLimitFilter(
        GithubWebhookProperties properties,
        GithubWebhookRateLimiter rateLimiter,
        ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod()) || !WEBHOOK_PATH.equals(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        if (!rateLimiter.tryAcquireIp(clientIp(request))) {
            reject(response, ErrorCode.TOO_MANY_REQUESTS, 429, "ip_rate_limit");
            return;
        }
        int limit = properties.getMaxPayloadBytes();
        if (request.getContentLengthLong() > limit) {
            reject(response, ErrorCode.PAYLOAD_TOO_LARGE, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "content_length");
            return;
        }
        byte[] payload = request.getInputStream().readNBytes(limit + 1);
        if (payload.length > limit) {
            reject(response, ErrorCode.PAYLOAD_TOO_LARGE, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "stream_limit");
            return;
        }
        filterChain.doFilter(new CachedBodyRequest(request, payload), response);
    }

    private void reject(HttpServletResponse response, ErrorCode code, int status, String reason) throws IOException {
        rateLimiter.rejected(reason);
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, "GitHub webhook request was rejected"));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Real-IP");
        return forwarded == null || forwarded.isBlank() ? request.getRemoteAddr() : forwarded;
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
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
                    return input.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
