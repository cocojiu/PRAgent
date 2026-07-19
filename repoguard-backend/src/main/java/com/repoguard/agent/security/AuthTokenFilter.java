package com.repoguard.agent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.authentication.AuthenticatedPrincipal;
import com.repoguard.agent.authentication.RequestAuthenticationAttributes;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.mapper.UserAccountMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.filter.OncePerRequestFilter;

public class AuthTokenFilter extends OncePerRequestFilter {

    private static final String STATUS_ACTIVE = "ACTIVE";

    private final AuthTokenService authTokenService;
    private final UserAccountMapper userAccountMapper;
    private final ObjectMapper objectMapper;

    public AuthTokenFilter(
        AuthTokenService authTokenService,
        UserAccountMapper userAccountMapper,
        ObjectMapper objectMapper
    ) {
        this.authTokenService = authTokenService;
        this.userAccountMapper = userAccountMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        if (CorsUtils.isPreFlightRequest(request) || !requiresAuth(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (request.getAttribute(RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL)
            instanceof AuthenticatedPrincipal) {
            filterChain.doFilter(request, response);
            return;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            writeUnauthorized(response, "Authentication token is required");
            return;
        }
        String token = header.substring("Bearer ".length()).trim();
        var authenticatedUser = authTokenService.verify(token);
        if (authenticatedUser.isEmpty()) {
            writeUnauthorized(response, "Authentication token is invalid or expired");
            return;
        }
        UserAccount currentUser = userAccountMapper.selectById(authenticatedUser.get().id());
        if (currentUser == null || !STATUS_ACTIVE.equals(currentUser.getStatus())) {
            writeUnauthorized(response, "Authentication token is invalid or expired");
            return;
        }
        if (safeSessionVersion(currentUser) != authenticatedUser.get().sessionVersion()) {
            writeUnauthorized(response, "Authentication token is invalid or expired");
            return;
        }
        request.setAttribute(RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL, new AuthenticatedPrincipal(
            currentUser.getId(),
            currentUser.getUsername(),
            currentUser.getRole(),
            authenticatedUser.get().expiresAt(),
            safeSessionVersion(currentUser)
        ));
        filterChain.doFilter(request, response);
    }

    private int safeSessionVersion(UserAccount user) {
        return user.getSessionVersion() == null ? 0 : user.getSessionVersion();
    }

    private boolean requiresAuth(HttpServletRequest request) {
        return AuthTokenAccessPolicy.requiresAuth(request.getMethod(), request.getRequestURI());
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
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
