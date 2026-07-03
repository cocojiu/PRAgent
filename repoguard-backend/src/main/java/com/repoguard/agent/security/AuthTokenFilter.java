package com.repoguard.agent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    public static final String AUTHENTICATED_USER_ATTRIBUTE = "repoguard.authenticatedUser";
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
        if (request.getAttribute(AUTHENTICATED_USER_ATTRIBUTE) instanceof AuthTokenService.AuthenticatedUser) {
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
        request.setAttribute(AUTHENTICATED_USER_ATTRIBUTE, new AuthTokenService.AuthenticatedUser(
            currentUser.getId(),
            currentUser.getUsername(),
            currentUser.getRole(),
            authenticatedUser.get().expiresAt()
        ));
        filterChain.doFilter(request, response);
    }

    private boolean requiresAuth(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path.equals("/api/v1/auth/me")) {
            return true;
        }
        if (path.equals("/api/v1/github/webhooks")) {
            return false;
        }
        return path.startsWith("/api/v1/")
            && !path.startsWith("/api/v1/auth/");
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
