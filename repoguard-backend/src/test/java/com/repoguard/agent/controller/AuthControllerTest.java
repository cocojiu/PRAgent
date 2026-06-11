package com.repoguard.agent.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.AuthLoginRequest;
import com.repoguard.agent.dto.AuthLogoutRequest;
import com.repoguard.agent.dto.AuthRefreshRequest;
import com.repoguard.agent.dto.AuthRefreshTokenResetRequest;
import com.repoguard.agent.dto.AuthRegisterRequest;
import com.repoguard.agent.dto.AuthResponse;
import com.repoguard.agent.dto.AuthUserDto;
import com.repoguard.agent.service.AuthService;
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
