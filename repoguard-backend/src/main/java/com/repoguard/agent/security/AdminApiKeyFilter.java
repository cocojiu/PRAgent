package com.repoguard.agent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

public class AdminApiKeyFilter extends OncePerRequestFilter {

    private final AdminApiKeyProperties properties;
    private final ObjectMapper objectMapper;

    public AdminApiKeyFilter(AdminApiKeyProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        if (!requiresAdminKey(request) || !properties.isProtectionActive()) {
            filterChain.doFilter(request, response);
            return;
        }

        String actualKey = request.getHeader(properties.getHeaderName());
        if (actualKey == null || actualKey.isBlank()) {
            writeError(response, HttpStatus.UNAUTHORIZED, "Admin API key is required");
            return;
        }
        if (!secureEquals(properties.getKey(), actualKey)) {
            writeError(response, HttpStatus.FORBIDDEN, "Admin API key is invalid");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean requiresAdminKey(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (path.startsWith("/api/v1/config/") || "/api/v1/config".equals(path)) {
            return true;
        }
        if (!"POST".equalsIgnoreCase(method)) {
            return false;
        }
        return "/api/v1/reviews/manual".equals(path)
            || path.matches("/api/v1/reviews/\\d+/retry")
            || path.matches("/api/v1/reviews/\\d+/github-comments")
            || path.matches("/api/v1/message-queue/tasks/\\d+/requeue");
    }

    private boolean secureEquals(String expected, String actual) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of(
            "success", false,
            "code", ErrorCode.UNAUTHORIZED.code(),
            "message", message,
            "timestamp", OffsetDateTime.now().toString()
        ));
    }
}
