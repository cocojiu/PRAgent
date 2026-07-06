package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.dto.AuthCurrentUserDto;
import com.repoguard.agent.dto.AuthLoginRequest;
import com.repoguard.agent.dto.AuthLogoutRequest;
import com.repoguard.agent.dto.AuthRefreshRequest;
import com.repoguard.agent.dto.AuthRefreshTokenResetRequest;
import com.repoguard.agent.dto.AuthRegisterRequest;
import com.repoguard.agent.dto.AuthResponse;
import com.repoguard.agent.security.AuthTokenFilter;
import com.repoguard.agent.security.AuthTokenService;
import com.repoguard.agent.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@ApiRuntimeEnabled
public class AuthController {

    static final String REFRESH_TOKEN_COOKIE_NAME = "repoguard_refresh_token";
    static final String CSRF_TOKEN_COOKIE_NAME = "repoguard_csrf_token";
    static final String CSRF_TOKEN_HEADER_NAME = "X-RepoGuard-CSRF";
    static final String LEGACY_REFRESH_TOKEN_FALLBACK_HEADER = "X-RepoGuard-Legacy-Refresh-Token-Fallback";
    private static final String LEGACY_REFRESH_TOKEN_FALLBACK_VALUE = "body-refresh-token";
    private static final String REFRESH_TOKEN_COOKIE_PATH = "/api/v1/auth";
    private static final String CSRF_TOKEN_COOKIE_PATH = "/";
    private static final SecureRandom CSRF_TOKEN_RANDOM = new SecureRandom();

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(
        @Valid @RequestBody AuthRegisterRequest request,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        AuthResponse response = authService.register(request);
        return authResponse(response, httpRequest, httpResponse);
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(
        @Valid @RequestBody AuthLoginRequest request,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        AuthResponse response = authService.login(request);
        return authResponse(response, httpRequest, httpResponse);
    }

    @GetMapping("/me")
    public ApiResponse<AuthCurrentUserDto> me(HttpServletRequest request) {
        Object authenticatedUser = request.getAttribute(AuthTokenFilter.AUTHENTICATED_USER_ATTRIBUTE);
        if (!(authenticatedUser instanceof AuthTokenService.AuthenticatedUser user)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Authentication token is required");
        }
        return ApiResponse.ok(authService.currentUser(user.id()));
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refresh(
        @Valid @RequestBody(required = false) AuthRefreshRequest request,
        @CookieValue(name = REFRESH_TOKEN_COOKIE_NAME, required = false) String cookieRefreshToken,
        @CookieValue(name = CSRF_TOKEN_COOKIE_NAME, required = false) String csrfCookieToken,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        validateCookieTokenCsrf(request, cookieRefreshToken, csrfCookieToken, httpRequest);
        try {
            AuthResponse response = authService.refresh(new AuthRefreshRequest(refreshToken(request, cookieRefreshToken)));
            markLegacyRefreshTokenFallbackIfNeeded(requestRefreshToken(request), cookieRefreshToken, httpResponse);
            return authResponse(response, httpRequest, httpResponse);
        } catch (RuntimeException ex) {
            clearAuthCookies(httpRequest, httpResponse);
            throw ex;
        }
    }

    @PostMapping("/refresh-token/reset")
    public ApiResponse<AuthResponse> resetRefreshToken(
        @Valid @RequestBody AuthRefreshTokenResetRequest request,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        AuthResponse response = authService.resetRefreshToken(request);
        return authResponse(response, httpRequest, httpResponse);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(
        @Valid @RequestBody(required = false) AuthLogoutRequest request,
        @CookieValue(name = REFRESH_TOKEN_COOKIE_NAME, required = false) String cookieRefreshToken,
        @CookieValue(name = CSRF_TOKEN_COOKIE_NAME, required = false) String csrfCookieToken,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        validateCookieTokenCsrf(request, cookieRefreshToken, csrfCookieToken, httpRequest);
        authService.logout(new AuthLogoutRequest(refreshToken(request, cookieRefreshToken)));
        markLegacyRefreshTokenFallbackIfNeeded(requestRefreshToken(request), cookieRefreshToken, httpResponse);
        clearAuthCookies(httpRequest, httpResponse);
        return ApiResponse.ok(null);
    }

    private String refreshToken(AuthRefreshRequest request, String cookieRefreshToken) {
        if (request != null && StringUtils.hasText(request.refreshToken())) {
            return request.refreshToken();
        }
        return cookieRefreshToken;
    }

    private String refreshToken(AuthLogoutRequest request, String cookieRefreshToken) {
        if (request != null && StringUtils.hasText(request.refreshToken())) {
            return request.refreshToken();
        }
        return cookieRefreshToken;
    }

    private String requestRefreshToken(AuthRefreshRequest request) {
        return request == null ? null : request.refreshToken();
    }

    private String requestRefreshToken(AuthLogoutRequest request) {
        return request == null ? null : request.refreshToken();
    }

    private void markLegacyRefreshTokenFallbackIfNeeded(
        String requestRefreshToken,
        String cookieRefreshToken,
        HttpServletResponse response
    ) {
        if (StringUtils.hasText(requestRefreshToken) && !StringUtils.hasText(cookieRefreshToken)) {
            response.addHeader(LEGACY_REFRESH_TOKEN_FALLBACK_HEADER, LEGACY_REFRESH_TOKEN_FALLBACK_VALUE);
        }
    }

    private ApiResponse<AuthResponse> authResponse(
        AuthResponse response,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        writeRefreshTokenCookie(response, httpRequest, httpResponse);
        return ApiResponse.ok(withoutRefreshToken(response));
    }

    private AuthResponse withoutRefreshToken(AuthResponse response) {
        return new AuthResponse(
            response.accessToken(),
            null,
            response.tokenType(),
            response.accessTokenExpiresInSeconds(),
            response.refreshTokenExpiresInSeconds(),
            response.user()
        );
    }

    private void writeRefreshTokenCookie(
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
        ResponseCookie cookie = refreshTokenCookieBuilder(request, authResponse.refreshToken())
            .maxAge(Duration.ofSeconds(refreshTokenTtlSeconds))
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        ResponseCookie csrfCookie = csrfTokenCookieBuilder(request, newCsrfToken())
            .maxAge(Duration.ofSeconds(refreshTokenTtlSeconds))
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, csrfCookie.toString());
    }

    private void clearAuthCookies(HttpServletRequest request, HttpServletResponse response) {
        ResponseCookie refreshCookie = refreshTokenCookieBuilder(request, "")
            .maxAge(Duration.ZERO)
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, refreshCookie.toString());
        ResponseCookie csrfCookie = csrfTokenCookieBuilder(request, "")
            .maxAge(Duration.ZERO)
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, csrfCookie.toString());
    }

    private ResponseCookie.ResponseCookieBuilder refreshTokenCookieBuilder(HttpServletRequest request, String value) {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE_NAME, value)
            .httpOnly(true)
            .secure(isSecureRequest(request))
            .sameSite("Lax")
            .path(REFRESH_TOKEN_COOKIE_PATH);
    }

    private ResponseCookie.ResponseCookieBuilder csrfTokenCookieBuilder(HttpServletRequest request, String value) {
        return ResponseCookie.from(CSRF_TOKEN_COOKIE_NAME, value)
            .httpOnly(false)
            .secure(isSecureRequest(request))
            .sameSite("Lax")
            .path(CSRF_TOKEN_COOKIE_PATH);
    }

    private void validateCookieTokenCsrf(
        AuthRefreshRequest request,
        String cookieRefreshToken,
        String csrfCookieToken,
        HttpServletRequest httpRequest
    ) {
        String requestRefreshToken = request == null ? null : request.refreshToken();
        validateCookieTokenCsrf(requestRefreshToken, cookieRefreshToken, csrfCookieToken, httpRequest);
    }

    private void validateCookieTokenCsrf(
        AuthLogoutRequest request,
        String cookieRefreshToken,
        String csrfCookieToken,
        HttpServletRequest httpRequest
    ) {
        String requestRefreshToken = request == null ? null : request.refreshToken();
        validateCookieTokenCsrf(requestRefreshToken, cookieRefreshToken, csrfCookieToken, httpRequest);
    }

    private void validateCookieTokenCsrf(
        String requestRefreshToken,
        String cookieRefreshToken,
        String csrfCookieToken,
        HttpServletRequest httpRequest
    ) {
        if (!StringUtils.hasText(cookieRefreshToken)) {
            return;
        }
        if (StringUtils.hasText(requestRefreshToken) && !secureEquals(cookieRefreshToken, requestRefreshToken)) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "Request refresh token must match cookie refresh token"
            );
        }
        String headerToken = httpRequest == null ? null : httpRequest.getHeader(CSRF_TOKEN_HEADER_NAME);
        if (!StringUtils.hasText(csrfCookieToken)
            || !StringUtils.hasText(headerToken)
            || !secureEquals(csrfCookieToken, headerToken)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "CSRF token is required for cookie refresh token requests");
        }
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

    private boolean isSecureRequest(HttpServletRequest request) {
        return request != null
            && (request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto")));
    }
}
