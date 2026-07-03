package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
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
import java.time.Duration;
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
public class AuthController {

    static final String REFRESH_TOKEN_COOKIE_NAME = "repoguard_refresh_token";
    private static final String REFRESH_TOKEN_COOKIE_PATH = "/api/v1/auth";

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
        writeRefreshTokenCookie(response, httpRequest, httpResponse);
        return ApiResponse.ok(response);
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(
        @Valid @RequestBody AuthLoginRequest request,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        AuthResponse response = authService.login(request);
        writeRefreshTokenCookie(response, httpRequest, httpResponse);
        return ApiResponse.ok(response);
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
        @RequestBody(required = false) AuthRefreshRequest request,
        @CookieValue(name = REFRESH_TOKEN_COOKIE_NAME, required = false) String cookieRefreshToken,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        try {
            AuthResponse response = authService.refresh(new AuthRefreshRequest(refreshToken(request, cookieRefreshToken)));
            writeRefreshTokenCookie(response, httpRequest, httpResponse);
            return ApiResponse.ok(response);
        } catch (RuntimeException ex) {
            clearRefreshTokenCookie(httpRequest, httpResponse);
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
        writeRefreshTokenCookie(response, httpRequest, httpResponse);
        return ApiResponse.ok(response);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(
        @RequestBody(required = false) AuthLogoutRequest request,
        @CookieValue(name = REFRESH_TOKEN_COOKIE_NAME, required = false) String cookieRefreshToken,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        authService.logout(new AuthLogoutRequest(refreshToken(request, cookieRefreshToken)));
        clearRefreshTokenCookie(httpRequest, httpResponse);
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
    }

    private void clearRefreshTokenCookie(HttpServletRequest request, HttpServletResponse response) {
        ResponseCookie cookie = refreshTokenCookieBuilder(request, "")
            .maxAge(Duration.ZERO)
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private ResponseCookie.ResponseCookieBuilder refreshTokenCookieBuilder(HttpServletRequest request, String value) {
        return ResponseCookie.from(REFRESH_TOKEN_COOKIE_NAME, value)
            .httpOnly(true)
            .secure(isSecureRequest(request))
            .sameSite("Lax")
            .path(REFRESH_TOKEN_COOKIE_PATH);
    }

    private boolean isSecureRequest(HttpServletRequest request) {
        return request != null
            && (request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto")));
    }
}
