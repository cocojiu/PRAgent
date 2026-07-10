package com.repoguard.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import com.repoguard.agent.web.AuthSessionCookieManager;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
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
            if (request == null || request.refreshToken() == null || request.refreshToken().isBlank()) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "refresh token expired or invalid");
            }
            if ("invalid-refresh-token".equals(request.refreshToken())) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "refresh token expired or invalid");
            }
            return authResponse("admin", "admin@repoguard.dev");
        }

        @Override
        public AuthResponse resetRefreshToken(AuthRefreshTokenResetRequest request) {
            return authResponse("admin", "admin@repoguard.dev");
        }

        @Override
        public void logout(AuthLogoutRequest request) {
            if (request == null || request.refreshToken() == null || request.refreshToken().isBlank()) {
                throw new BusinessException(ErrorCode.UNAUTHORIZED, "refresh token is required");
            }
        }
    };

    private final MockMvc mockMvc = MockMvcBuilders
        .standaloneSetup(new AuthController(authService, new AuthSessionCookieManager()))
        .setControllerAdvice(new com.repoguard.agent.common.GlobalExceptionHandler())
        .build();

    @Test
    void registerReturnsAccessTokenCookieAndUserWithoutRefreshTokenBody() throws Exception {
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
            .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
            .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("repoguard_refresh_token=refresh-token-value")))
            .andExpect(AuthControllerTest::expectReadableCsrfCookie)
            .andExpect(jsonPath("$.data.user.username").value("admin"));
    }

    @Test
    void loginReturnsAccessTokenCookieAndUserWithoutRefreshTokenBody() throws Exception {
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
            .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
            .andExpect(jsonPath("$.data.accessTokenExpiresInSeconds").value(900))
            .andExpect(jsonPath("$.data.refreshTokenExpiresInSeconds").value(7200))
            .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("repoguard_refresh_token=refresh-token-value")))
            .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
            .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Lax")))
            .andExpect(AuthControllerTest::expectReadableCsrfCookie);
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
    void refreshRejectsBodyRefreshTokenFallback() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "refreshToken": "refresh-token-value"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void refreshAcceptsHttpOnlyCookieToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                .cookie(refreshCookie(), csrfCookie())
                .header(AuthController.CSRF_TOKEN_HEADER_NAME, "csrf-token-value"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accessToken").value("access-token-value"))
            .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
            .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("repoguard_refresh_token=refresh-token-value")))
            .andExpect(AuthControllerTest::expectReadableCsrfCookie);
    }

    @Test
    void refreshWithEmptyBodyUsesHttpOnlyCookieToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .cookie(refreshCookie(), csrfCookie())
                .header(AuthController.CSRF_TOKEN_HEADER_NAME, "csrf-token-value"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accessToken").value("access-token-value"))
            .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("repoguard_refresh_token=refresh-token-value")));
    }

    @Test
    void refreshWithCookieTokenRequiresCsrfHeader() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                .cookie(refreshCookie(), csrfCookie()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void refreshWithCookieAndBodyTokenReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "refreshToken": "refresh-token-value"
                    }
                    """)
                .cookie(refreshCookie(), csrfCookie()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void refreshWithCookieTokenRejectsMismatchedCsrfHeader() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                .cookie(refreshCookie(), csrfCookie())
                .header(AuthController.CSRF_TOKEN_HEADER_NAME, "different-token"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void refreshWithCookieAndDifferentBodyTokenReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "refreshToken": "different-refresh-token"
                    }
                    """)
                .cookie(refreshCookie(), csrfCookie())
                .header(AuthController.CSRF_TOKEN_HEADER_NAME, "csrf-token-value"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void refreshWithoutCookieReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void refreshWithInvalidCookieTokenClearsAuthCookies() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                .cookie(new Cookie(AuthController.REFRESH_TOKEN_COOKIE_NAME, "invalid-refresh-token"), csrfCookie())
                .header(AuthController.CSRF_TOKEN_HEADER_NAME, "csrf-token-value"))
            .andExpect(status().isUnauthorized())
            .andExpect(result -> expectSetCookieContains(result, "repoguard_refresh_token="))
            .andExpect(result -> expectSetCookieContains(result, "repoguard_csrf_token="))
            .andExpect(result -> expectSetCookieContains(result, "Max-Age=0"));
    }

    @Test
    void refreshWithMalformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{refreshToken:invalid-refresh-token}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void refreshWithOversizedTokenReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "refreshToken": "%s"
                    }
                    """.formatted("r".repeat(513))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void resetRefreshTokenReturnsNewAccessTokenWithoutRefreshTokenBody() throws Exception {
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
            .andExpect(jsonPath("$.data.accessToken").value("access-token-value"))
            .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
            .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("repoguard_refresh_token=refresh-token-value")))
            .andExpect(AuthControllerTest::expectReadableCsrfCookie);
    }

    @Test
    void resetRefreshTokenWithOversizedCredentialsReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh-token/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "account": "%s",
                      "password": "%s",
                      "remember": false
                    }
                    """.formatted("a".repeat(256), "p".repeat(129))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void logoutRejectsBodyRefreshTokenFallback() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "refreshToken": "refresh-token-value"
                    }
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void logoutClearsHttpOnlyCookieToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
            .cookie(refreshCookie(), csrfCookie())
            .header(AuthController.CSRF_TOKEN_HEADER_NAME, "csrf-token-value"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("repoguard_refresh_token=")))
            .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")))
            .andExpect(result -> expectSetCookieContains(result, "repoguard_csrf_token="));
    }

    @Test
    void logoutWithEmptyBodyUsesHttpOnlyCookieToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .cookie(refreshCookie(), csrfCookie())
                .header(AuthController.CSRF_TOKEN_HEADER_NAME, "csrf-token-value"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("repoguard_refresh_token=")))
            .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
    }

    @Test
    void logoutWithUnsupportedContentTypeReturns415() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .content("refreshToken=ignored")
                .cookie(refreshCookie(), csrfCookie())
                .header(AuthController.CSRF_TOKEN_HEADER_NAME, "csrf-token-value"))
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.message").value("Content type is not supported"));
    }

    @Test
    void logoutWithCookieTokenRequiresCsrfHeader() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                .cookie(refreshCookie(), csrfCookie()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void logoutWithCookieAndBodyTokenReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "refreshToken": "refresh-token-value"
                    }
                    """)
                .cookie(refreshCookie(), csrfCookie()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void logoutWithCookieAndDifferentBodyTokenReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "refreshToken": "different-refresh-token"
                    }
                    """)
                .cookie(refreshCookie(), csrfCookie())
                .header(AuthController.CSRF_TOKEN_HEADER_NAME, "csrf-token-value"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void logoutWithOversizedTokenReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "refreshToken": "%s"
                    }
                    """.formatted("r".repeat(513))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
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

    @Test
    void loginWithMalformedJsonReturns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{account:admin,password:wrong}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
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

    private static Cookie refreshCookie() {
        return new Cookie(AuthController.REFRESH_TOKEN_COOKIE_NAME, "refresh-token-value");
    }

    private static Cookie csrfCookie() {
        return new Cookie(AuthController.CSRF_TOKEN_COOKIE_NAME, "csrf-token-value");
    }

    private static void expectReadableCsrfCookie(MvcResult result) {
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
            .anySatisfy(cookie -> assertThat(cookie)
                .contains("repoguard_csrf_token=")
                .contains("Path=/")
                .contains("SameSite=Lax")
                .doesNotContain("HttpOnly"));
    }

    private static void expectSetCookieContains(MvcResult result, String expected) {
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
            .anySatisfy(cookie -> assertThat(cookie).contains(expected));
    }
}
