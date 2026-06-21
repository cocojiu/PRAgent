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
        if (hasBearerToken(request)) {
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
        request.setAttribute(
            AuthTokenFilter.AUTHENTICATED_USER_ATTRIBUTE,
            new AuthTokenService.AuthenticatedUser(0L, "admin-api-key", "ADMIN", Long.MAX_VALUE)
        );
        filterChain.doFilter(request, response);
    }

    private boolean hasBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        return authorization != null && authorization.startsWith("Bearer ") && authorization.length() > "Bearer ".length();
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
            || reviewTaskActionPath(path, "retry")
            || reviewTaskActionPath(path, "human-review")
            || reviewTaskActionPath(path, "github-comments")
            || reviewFindingFeedbackPath(path)
            || messageQueueRequeuePath(path);
    }

    private boolean reviewTaskActionPath(String path, String action) {
        String prefix = "/api/v1/reviews/";
        String suffix = "/" + action;
        if (!path.startsWith(prefix) || !path.endsWith(suffix)) {
            return false;
        }
        return isPositiveNumeric(path, prefix.length(), path.length() - suffix.length());
    }

    private boolean reviewFindingFeedbackPath(String path) {
        String prefix = "/api/v1/reviews/";
        String marker = "/findings/";
        String suffix = "/feedback";
        if (!path.startsWith(prefix) || !path.endsWith(suffix)) {
            return false;
        }
        int markerIndex = path.indexOf(marker, prefix.length());
        if (markerIndex < 0) {
            return false;
        }
        return isPositiveNumeric(path, prefix.length(), markerIndex)
            && isPositiveNumeric(path, markerIndex + marker.length(), path.length() - suffix.length());
    }

    private boolean messageQueueRequeuePath(String path) {
        String prefix = "/api/v1/message-queue/tasks/";
        String suffix = "/requeue";
        if (!path.startsWith(prefix) || !path.endsWith(suffix)) {
            return false;
        }
        return isPositiveNumeric(path, prefix.length(), path.length() - suffix.length());
    }

    private boolean isPositiveNumeric(String value, int startInclusive, int endExclusive) {
        if (startInclusive >= endExclusive) {
            return false;
        }
        for (int index = startInclusive; index < endExclusive; index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
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
