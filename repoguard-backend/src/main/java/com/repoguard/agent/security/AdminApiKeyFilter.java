package com.repoguard.agent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.authentication.AuthenticatedPrincipal;
import com.repoguard.agent.authentication.RequestAuthenticationAttributes;
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
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;

public class AdminApiKeyFilter extends OncePerRequestFilter {

    private final AdminApiKeyProperties properties;
    private final AuthTokenService authTokenService;
    private final ObjectMapper objectMapper;
    private final Predicate<HttpServletRequest> failedAttemptAllowed;
    private final BiConsumer<HttpServletRequest, String> failureAudit;

    AdminApiKeyFilter(
        AdminApiKeyProperties properties,
        AuthTokenService authTokenService,
        ObjectMapper objectMapper
    ) {
        this(properties, authTokenService, objectMapper, ignored -> true, (ignored, category) -> {});
    }

    public AdminApiKeyFilter(
        AdminApiKeyProperties properties,
        AuthTokenService authTokenService,
        ObjectMapper objectMapper,
        AdminApiKeyAttemptLimiter attemptLimiter,
        AdminApiKeyFailureAuditRecorder failureAuditRecorder
    ) {
        this(
            properties,
            authTokenService,
            objectMapper,
            attemptLimiter::recordFailureAllowed,
            failureAuditRecorder::record
        );
    }

    private AdminApiKeyFilter(
        AdminApiKeyProperties properties,
        AuthTokenService authTokenService,
        ObjectMapper objectMapper,
        Predicate<HttpServletRequest> failedAttemptAllowed,
        BiConsumer<HttpServletRequest, String> failureAudit
    ) {
        this.properties = properties;
        this.authTokenService = authTokenService;
        this.objectMapper = objectMapper;
        this.failedAttemptAllowed = failedAttemptAllowed;
        this.failureAudit = failureAudit;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        if (CorsUtils.isPreFlightRequest(request) || !properties.isProtectionActive() || !requiresAdminKey(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (hasVerifiedBearerToken(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String actualKey = request.getHeader(properties.getHeaderName());
        if (actualKey == null || actualKey.isBlank()) {
            rejectInvalidCredential(request, response, "ADMIN_API_KEY_MISSING");
            return;
        }
        if (!secureEquals(properties.getKey(), actualKey)) {
            rejectInvalidCredential(request, response, "ADMIN_API_KEY_INVALID");
            return;
        }
        request.setAttribute(
            RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL,
            new AuthenticatedPrincipal(0L, "admin-api-key", "ADMIN", Long.MAX_VALUE)
        );
        filterChain.doFilter(request, response);
    }

    private void rejectInvalidCredential(
        HttpServletRequest request,
        HttpServletResponse response,
        String failureCategory
    ) throws IOException {
        if (!failedAttemptAllowed.test(request)) {
            failureAudit.accept(request, "ADMIN_API_KEY_RATE_LIMITED");
            writeError(
                response,
                HttpStatus.TOO_MANY_REQUESTS,
                ErrorCode.TOO_MANY_REQUESTS,
                "Too many Admin API key authentication attempts"
            );
            return;
        }
        failureAudit.accept(request, failureCategory);
        writeError(
            response,
            HttpStatus.UNAUTHORIZED,
            ErrorCode.UNAUTHORIZED,
            "Admin API key is invalid or missing"
        );
    }

    private boolean hasVerifiedBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        return authTokenService.verify(authorization.substring("Bearer ".length()).trim()).isPresent();
    }

    private boolean requiresAdminKey(HttpServletRequest request) {
        return AdminApiKeyAccessPolicy.requiresAdminKey(
            request.getMethod(),
            ServletRequestPathUtils.parseAndCache(request).pathWithinApplication().value()
        );
    }

    private boolean secureEquals(String expected, String actual) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

    private void writeError(HttpServletResponse response, HttpStatus status, ErrorCode errorCode, String message) throws IOException {
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of(
            "success", false,
            "code", errorCode.code(),
            "message", message,
            "timestamp", OffsetDateTime.now().toString()
        ));
    }
}
