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
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@Valid @RequestBody AuthRegisterRequest request) {
        return ApiResponse.ok(authService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody AuthLoginRequest request) {
        return ApiResponse.ok(authService.login(request));
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
    public ApiResponse<AuthResponse> refresh(@Valid @RequestBody AuthRefreshRequest request) {
        return ApiResponse.ok(authService.refresh(request));
    }

    @PostMapping("/refresh-token/reset")
    public ApiResponse<AuthResponse> resetRefreshToken(@Valid @RequestBody AuthRefreshTokenResetRequest request) {
        return ApiResponse.ok(authService.resetRefreshToken(request));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@Valid @RequestBody AuthLogoutRequest request) {
        authService.logout(request);
        return ApiResponse.ok(null);
    }
}
