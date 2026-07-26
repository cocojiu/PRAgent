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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ServletRequestPathUtils;

public class AdminApiKeyFilter extends OncePerRequestFilter {

    private final AdminApiKeyProperties properties;
    private final AuthTokenService authTokenService;
    private final ObjectMapper objectMapper;

    public AdminApiKeyFilter(
        AdminApiKeyProperties properties,
        AuthTokenService authTokenService,
        ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.authTokenService = authTokenService;
        this.objectMapper = objectMapper;
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
            writeError(response, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "Admin API key is required");
            return;
        }
        if (!secureEquals(properties.getKey(), actualKey)) {
            writeError(response, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "Admin API key is invalid");
            return;
        }
        request.setAttribute(
            RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL,
            new AuthenticatedPrincipal(0L, "admin-api-key", "ADMIN", Long.MAX_VALUE)
        );
        filterChain.doFilter(request, response);
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
