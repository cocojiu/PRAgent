package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.entity.UserAccount;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthTokenFilterTest {

    private final AuthProperties authProperties = properties();
    private final AuthTokenService authTokenService = new AuthTokenService(authProperties);
    private final AuthTokenFilter filter = new AuthTokenFilter(authTokenService, new ObjectMapper());

    @Test
    void authEndpointDoesNotRequireToken() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void authMeEndpointRequiresToken() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void authMeEndpointAllowsValidToken() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        request.addHeader("Authorization", "Bearer " + authTokenService.issueAccessToken(user()).token());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(request.getAttribute(AuthTokenFilter.AUTHENTICATED_USER_ATTRIBUTE))
            .isInstanceOf(AuthTokenService.AuthenticatedUser.class);
    }

    @Test
    void protectedApiRejectsMissingToken() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reviews");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    void protectedApiAllowsExistingAuthenticatedUserAttribute() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reviews");
        request.setAttribute(
            AuthTokenFilter.AUTHENTICATED_USER_ATTRIBUTE,
            new AuthTokenService.AuthenticatedUser(0L, "admin-api-key", "ADMIN", Long.MAX_VALUE)
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void protectedApiRejectsInvalidToken() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reviews");
        request.addHeader("Authorization", "Bearer invalid");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void protectedApiAllowsValidTokenAndSetsRequestAttribute() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reviews");
        request.addHeader("Authorization", "Bearer " + authTokenService.issueAccessToken(user()).token());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(request.getAttribute(AuthTokenFilter.AUTHENTICATED_USER_ATTRIBUTE))
            .isInstanceOf(AuthTokenService.AuthenticatedUser.class);
    }

    private AuthProperties properties() {
        AuthProperties properties = new AuthProperties();
        properties.setTokenSecret("test-secret");
        return properties;
    }

    private UserAccount user() {
        UserAccount user = new UserAccount();
        user.setId(1001L);
        user.setUsername("admin");
        user.setRole("ADMIN");
        return user;
    }
}
