package com.repoguard.agent.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.AuthCurrentUserDto;
import com.repoguard.agent.dto.AuthLoginRequest;
import com.repoguard.agent.dto.AuthLogoutRequest;
import com.repoguard.agent.dto.AuthRefreshRequest;
import com.repoguard.agent.dto.AuthRefreshTokenResetRequest;
import com.repoguard.agent.dto.AuthRegisterRequest;
import com.repoguard.agent.dto.AuthResponse;
import com.repoguard.agent.dto.AuthUserDto;
import com.repoguard.agent.security.AuthTokenFilter;
import com.repoguard.agent.security.AuthTokenService;
import com.repoguard.agent.service.AuthService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthControllerTest {

    private final AuthService authService = new AuthService() {
        @Override
        public AuthResponse register(AuthRegisterRequest request) {
            return authResponse(request.username(), request.email());
        }

        @Override
        public AuthResponse login(AuthLoginRequest request) {
            if ("wrong".equals(request.password())) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "账号或密码错误");
            }
            return authResponse("admin", "admin@repoguard.dev");
        }

        @Override
        public AuthCurrentUserDto currentUser(Long userId) {
            return new AuthCurrentUserDto(
                userId,
                "admin",
                "admin@repoguard.dev",
                "ADMIN",
                "ACTIVE",
                LocalDateTime.parse("2026-06-11T10:00:00")
            );
        }

        @Override
        public AuthResponse refresh(AuthRefreshRequest request) {
            return authResponse("admin", "admin@repoguard.dev");
        }

        @Override
        public AuthResponse resetRefreshToken(AuthRefreshTokenResetRequest request) {
            return authResponse("admin", "admin@repoguard.dev");
        }

        @Override
        public void logout(AuthLogoutRequest request) {
        }
    };

    private final MockMvc mockMvc = MockMvcBuilders
        .standaloneSetup(new AuthController(authService))
        .setControllerAdvice(new com.repoguard.agent.common.GlobalExceptionHandler())
        .build();

    @Test
    void registerReturnsTokenPairAndUser() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "username": "admin",
                      "email": "admin@repoguard.dev",
                      "password": "Secure123",
                      "confirmPassword": "Secure123"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.accessToken").value("access-token-value"))
            .andExpect(jsonPath("$.data.refreshToken").value("refresh-token-value"))
            .andExpect(jsonPath("$.data.user.username").value("admin"));
    }

    @Test
    void loginReturnsTokenPairAndUser() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "account": "admin",
                      "password": "Secure123",
                      "remember": true
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.data.accessTokenExpiresInSeconds").value(900))
            .andExpect(jsonPath("$.data.refreshTokenExpiresInSeconds").value(7200));
    }

    @Test
    void meReturnsAuthenticatedUserProfile() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                .requestAttr(
                    AuthTokenFilter.AUTHENTICATED_USER_ATTRIBUTE,
                    new AuthTokenService.AuthenticatedUser(1001L, "admin", "ADMIN", 9999999999L)
                ))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value(1001))
            .andExpect(jsonPath("$.data.username").value("admin"))
            .andExpect(jsonPath("$.data.email").value("admin@repoguard.dev"))
            .andExpect(jsonPath("$.data.role").value("ADMIN"))
            .andExpect(jsonPath("$.data.status").value("ACTIVE"))
            .andExpect(jsonPath("$.data.lastLoginAt").value("2026-06-11T10:00:00"));
    }

    @Test
    void meWithoutAuthenticatedUserReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void refreshReturnsNewTokenPair() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "refreshToken": "refresh-token-value"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accessToken").value("access-token-value"))
            .andExpect(jsonPath("$.data.refreshToken").value("refresh-token-value"));
    }

    @Test
    void resetRefreshTokenReturnsNewTokenPair() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh-token/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "account": "admin",
                      "password": "Secure123",
                      "remember": false
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accessToken").value("access-token-value"));
    }

    @Test
    void logoutReturnsOk() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "refreshToken": "refresh-token-value"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void loginWithWrongPasswordReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "account": "admin",
                      "password": "wrong",
                      "remember": false
                    }
                    """))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private AuthResponse authResponse(String username, String email) {
        return new AuthResponse(
            "access-token-value",
            "refresh-token-value",
            "Bearer",
            900L,
            7200L,
            new AuthUserDto(1001L, username, email, "ADMIN")
        );
    }
}
