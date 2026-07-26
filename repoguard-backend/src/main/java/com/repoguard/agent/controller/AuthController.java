package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.dto.AuthCurrentUserDto;
import com.repoguard.agent.dto.AuthLoginRequest;
import com.repoguard.agent.dto.AuthLogoutRequest;
import com.repoguard.agent.dto.AuthPasswordChangeRequest;
import com.repoguard.agent.dto.AuthRefreshRequest;
import com.repoguard.agent.dto.AuthRefreshTokenResetRequest;
import com.repoguard.agent.dto.AuthRegisterRequest;
import com.repoguard.agent.dto.AuthResponse;
import com.repoguard.agent.security.AllowAnonymous;
import com.repoguard.agent.security.AuthAttemptLimiter;
import com.repoguard.agent.security.RequireRole;
import com.repoguard.agent.service.AuthService;
import com.repoguard.agent.web.AuditClientIpResolver;
import com.repoguard.agent.web.AuthSessionCookieManager;
import com.repoguard.agent.web.RequestAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.util.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
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

    static final String REFRESH_TOKEN_COOKIE_NAME = AuthSessionCookieManager.REFRESH_TOKEN_COOKIE_NAME;
    static final String CSRF_TOKEN_COOKIE_NAME = AuthSessionCookieManager.CSRF_TOKEN_COOKIE_NAME;
    static final String CSRF_TOKEN_HEADER_NAME = AuthSessionCookieManager.CSRF_TOKEN_HEADER_NAME;

    private final AuthService authService;
    private final AuthSessionCookieManager cookieManager;
    private final AuthAttemptLimiter attemptLimiter;
    private final AuditClientIpResolver clientIpResolver;

    @Autowired
    public AuthController(
        AuthService authService,
        AuthSessionCookieManager cookieManager,
        AuthAttemptLimiter attemptLimiter,
        AuditClientIpResolver clientIpResolver
    ) {
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
        this.cookieManager = Objects.requireNonNull(cookieManager, "cookieManager must not be null");
        this.attemptLimiter = attemptLimiter;
        this.clientIpResolver = clientIpResolver;
    }

    public AuthController(AuthService authService, AuthSessionCookieManager cookieManager) {
        this.authService = Objects.requireNonNull(authService, "authService must not be null");
        this.cookieManager = Objects.requireNonNull(cookieManager, "cookieManager must not be null");
        this.attemptLimiter = null;
        this.clientIpResolver = null;
    }

    @AllowAnonymous
    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(
        @Valid @RequestBody AuthRegisterRequest request,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        limit("register", request.username(), httpRequest);
        AuthResponse response = authService.register(request);
        return authResponse(response, httpRequest, httpResponse);
    }

    @AllowAnonymous
    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(
        @Valid @RequestBody AuthLoginRequest request,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        limit("login", request.account(), httpRequest);
        AuthResponse response = authService.login(request);
        return authResponse(response, httpRequest, httpResponse);
    }

    @GetMapping("/me")
    public ApiResponse<AuthCurrentUserDto> me(HttpServletRequest request) {
        var user = RequestAuthentication.require(request);
        return ApiResponse.ok(authService.currentUser(user.id()));
    }

    @RequireRole({"ADMIN", "VIEWER"})
    @PostMapping("/password/change")
    public ApiResponse<Void> changePassword(
        @Valid @RequestBody AuthPasswordChangeRequest passwordChangeRequest,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        var user = RequestAuthentication.require(httpRequest);
        limit("password-change", user.username(), httpRequest);
        authService.changePassword(user.id(), passwordChangeRequest);
        cookieManager.clearAuthCookies(httpRequest, httpResponse);
        return ApiResponse.ok(null);
    }

    @AllowAnonymous
    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refresh(
        @Valid @RequestBody(required = false) AuthRefreshRequest request,
        @CookieValue(name = REFRESH_TOKEN_COOKIE_NAME, required = false) String cookieRefreshToken,
        @CookieValue(name = CSRF_TOKEN_COOKIE_NAME, required = false) String csrfCookieToken,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        limit("refresh", refreshTokenBucketKey(cookieRefreshToken), httpRequest);
        rejectBodyRefreshToken(request);
        cookieManager.validateCookieTokenCsrf(cookieRefreshToken, csrfCookieToken, httpRequest);
        AuthResponse response = authService.refresh(new AuthRefreshRequest(cookieRefreshToken));
        return authResponse(response, httpRequest, httpResponse);
    }

    @AllowAnonymous
    @PostMapping("/refresh-token/reset")
    public ApiResponse<AuthResponse> resetRefreshToken(
        @Valid @RequestBody AuthRefreshTokenResetRequest request,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        limit("reset", request.account(), httpRequest);
        AuthResponse response = authService.resetRefreshToken(request);
        return authResponse(response, httpRequest, httpResponse);
    }

    @AllowAnonymous
    @PostMapping("/logout")
    public ApiResponse<Void> logout(
        @Valid @RequestBody(required = false) AuthLogoutRequest request,
        @CookieValue(name = REFRESH_TOKEN_COOKIE_NAME, required = false) String cookieRefreshToken,
        @CookieValue(name = CSRF_TOKEN_COOKIE_NAME, required = false) String csrfCookieToken,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        rejectBodyRefreshToken(request);
        cookieManager.validateCookieTokenCsrf(cookieRefreshToken, csrfCookieToken, httpRequest);
        authService.logout(new AuthLogoutRequest(cookieRefreshToken));
        cookieManager.clearAuthCookies(httpRequest, httpResponse);
        return ApiResponse.ok(null);
    }

    private void rejectBodyRefreshToken(AuthRefreshRequest request) {
        if (request != null && StringUtils.hasText(request.refreshToken())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Request body refresh token is no longer supported");
        }
    }

    private void rejectBodyRefreshToken(AuthLogoutRequest request) {
        if (request != null && StringUtils.hasText(request.refreshToken())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Request body refresh token is no longer supported");
        }
    }

    private ApiResponse<AuthResponse> authResponse(
        AuthResponse response,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        cookieManager.writeRefreshTokenCookies(response, httpRequest, httpResponse);
        return ApiResponse.ok(withoutRefreshToken(response));
    }

    private void limit(String operation, String account, HttpServletRequest request) {
        if (attemptLimiter != null) {
            attemptLimiter.requireAllowed(operation, account, clientIpResolver.resolve(request));
        }
    }

    private static String refreshTokenBucketKey(String refreshToken) {
        if (!StringUtils.hasText(refreshToken)) {
            return null;
        }
        try {
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(refreshToken.getBytes(StandardCharsets.UTF_8)))
                .substring(0, 16);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Refresh token hashing is not available", ex);
        }
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
}
