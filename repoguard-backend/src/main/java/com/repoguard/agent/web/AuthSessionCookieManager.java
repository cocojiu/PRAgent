package com.repoguard.agent.web;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.AuthResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AuthSessionCookieManager {

    public static final String REFRESH_TOKEN_COOKIE_NAME = "repoguard_refresh_token";
    public static final String CSRF_TOKEN_COOKIE_NAME = "repoguard_csrf_token";
    public static final String CSRF_TOKEN_HEADER_NAME = "X-RepoGuard-CSRF";

    private static final String REFRESH_TOKEN_COOKIE_PATH = "/api/v1/auth";
    private static final String CSRF_TOKEN_COOKIE_PATH = "/";
    private static final SecureRandom CSRF_TOKEN_RANDOM = new SecureRandom();
    private final boolean forceSecureCookies;

    public AuthSessionCookieManager(@Value("${repoguard.auth.secure-cookies:false}") boolean forceSecureCookies) {
        this.forceSecureCookies = forceSecureCookies;
    }

    public void writeRefreshTokenCookies(
        AuthResponse authResponse,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        if (authResponse == null || !StringUtils.hasText(authResponse.refreshToken())) {
            return;
        }
        long refreshTokenTtlSeconds = authResponse.refreshTokenExpiresInSeconds() == null
            ? 0L
            : Math.max(0L, authResponse.refreshTokenExpiresInSeconds());
        ResponseCookie refreshCookie = refreshTokenCookieBuilder(request, authResponse.refreshToken())
            .maxAge(Duration.ofSeconds(refreshTokenTtlSeconds))
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());
        ResponseCookie csrfCookie = csrfTokenCookieBuilder(request, newCsrfToken())
            .maxAge(Duration.ofSeconds(refreshTokenTtlSeconds))
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, csrfCookie.toString());
    }

    public void clearAuthCookies(HttpServletRequest request, HttpServletResponse response) {
        ResponseCookie refreshCookie = refreshTokenCookieBuilder(request, "")
            .maxAge(Duration.ZERO)
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());
        ResponseCookie csrfCookie = csrfTokenCookieBuilder(request, "")
            .maxAge(Duration.ZERO)
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, csrfCookie.toString());
    }

    public void validateCookieTokenCsrf(
        String cookieRefreshToken,
        String csrfCookieToken,
        HttpServletRequest httpRequest
    ) {
        if (!StringUtils.hasText(cookieRefreshToken)) {
            return;
        }
        String headerToken = httpRequest == null ? null : httpRequest.getHeader(CSRF_TOKEN_HEADER_NAME);
        if (!StringUtils.hasText(csrfCookieToken)
            || !StringUtils.hasText(headerToken)
            || !secureEquals(csrfCookieToken, headerToken)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "CSRF token is required for cookie refresh token requests");
        }
    }

    private ResponseCookie.ResponseCookieBuilder refreshTokenCookieBuilder(HttpServletRequest request, String value) {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE_NAME, value)
            .httpOnly(true)
            .secure(shouldUseSecureCookies(request))
            .sameSite("Lax")
            .path(REFRESH_TOKEN_COOKIE_PATH);
    }

    private ResponseCookie.ResponseCookieBuilder csrfTokenCookieBuilder(HttpServletRequest request, String value) {
        return ResponseCookie.from(CSRF_TOKEN_COOKIE_NAME, value)
            .httpOnly(false)
            .secure(shouldUseSecureCookies(request))
            .sameSite("Lax")
            .path(CSRF_TOKEN_COOKIE_PATH);
    }

    private String newCsrfToken() {
        byte[] bytes = new byte[32];
        CSRF_TOKEN_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private boolean secureEquals(String expected, String actual) {
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            actual.getBytes(StandardCharsets.UTF_8)
        );
    }

    private boolean shouldUseSecureCookies(HttpServletRequest request) {
        return forceSecureCookies
            || request != null
            && (request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto")));
    }
}
