package com.repoguard.agent.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.AuthResponse;
import com.repoguard.agent.dto.AuthUserDto;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthSessionCookieManagerTest {

    private final AuthSessionCookieManager manager = new AuthSessionCookieManager();

    @Test
    void writeRefreshTokenCookiesUsesHttpOnlyRefreshAndReadableCsrfCookies() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        manager.writeRefreshTokenCookies(authResponse(7200L), new MockHttpServletRequest(), response);

        List<String> cookies = response.getHeaders(HttpHeaders.SET_COOKIE);
        assertThat(cookies)
            .anySatisfy(cookie -> assertThat(cookie)
                .contains("repoguard_refresh_token=refresh-token-value")
                .contains("Max-Age=7200")
                .contains("Path=/api/v1/auth")
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .doesNotContain("Secure"));
        assertThat(cookies)
            .anySatisfy(cookie -> assertThat(cookie)
                .contains("repoguard_csrf_token=")
                .contains("Max-Age=7200")
                .contains("Path=/")
                .contains("SameSite=Lax")
                .doesNotContain("HttpOnly"));
    }

    @Test
    void writeRefreshTokenCookiesMarksSecureWhenForwardedProtoIsHttps() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-Proto", "https");
        MockHttpServletResponse response = new MockHttpServletResponse();

        manager.writeRefreshTokenCookies(authResponse(7200L), request, response);

        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
            .allSatisfy(cookie -> assertThat(cookie).contains("Secure"));
    }

    @Test
    void writeRefreshTokenCookiesSkipsMissingRefreshToken() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AuthResponse authResponse = new AuthResponse(
            "access-token-value",
            null,
            "Bearer",
            900L,
            7200L,
            new AuthUserDto(1001L, "admin", "admin@repoguard.dev", "ADMIN")
        );

        manager.writeRefreshTokenCookies(authResponse, new MockHttpServletRequest(), response);

        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    @Test
    void clearAuthCookiesExpiresRefreshAndCsrfCookies() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        manager.clearAuthCookies(new MockHttpServletRequest(), response);

        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
            .anySatisfy(cookie -> assertThat(cookie)
                .contains("repoguard_refresh_token=")
                .contains("Max-Age=0")
                .contains("Path=/api/v1/auth"));
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE))
            .anySatisfy(cookie -> assertThat(cookie)
                .contains("repoguard_csrf_token=")
                .contains("Max-Age=0")
                .contains("Path=/"));
    }

    @Test
    void validateCookieTokenCsrfAllowsMissingRefreshCookie() {
        manager.validateCookieTokenCsrf(null, null, new MockHttpServletRequest());
    }

    @Test
    void validateCookieTokenCsrfAcceptsMatchingHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(AuthSessionCookieManager.CSRF_TOKEN_HEADER_NAME, "csrf-token-value");

        manager.validateCookieTokenCsrf("refresh-token-value", "csrf-token-value", request);
    }

    @Test
    void validateCookieTokenCsrfRejectsMissingOrMismatchedHeader() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(AuthSessionCookieManager.CSRF_TOKEN_HEADER_NAME, "different-token");

        assertThatThrownBy(() -> manager.validateCookieTokenCsrf("refresh-token-value", "csrf-token-value", request))
            .isInstanceOf(BusinessException.class)
            .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }

    private AuthResponse authResponse(Long refreshTokenExpiresInSeconds) {
        return new AuthResponse(
            "access-token-value",
            "refresh-token-value",
            "Bearer",
            900L,
            refreshTokenExpiresInSeconds,
            new AuthUserDto(1001L, "admin", "admin@repoguard.dev", "ADMIN")
        );
    }
}
