package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.mapper.UserAccountMapper;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthTokenFilterTest {

    private final AuthProperties authProperties = properties();
    private final AuthTokenService authTokenService = new AuthTokenService(authProperties);
    private final UserAccountMapper userAccountMapper = Mockito.mock(UserAccountMapper.class);
    private final AuthTokenFilter filter = new AuthTokenFilter(authTokenService, userAccountMapper, new ObjectMapper());

    @BeforeEach
    void setUp() {
        Mockito.reset(userAccountMapper);
    }

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
        Mockito.when(userAccountMapper.selectById(1001L)).thenReturn(user());

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
    void corsPreflightDoesNotRequireToken() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/reviews");
        request.addHeader("Origin", "http://localhost:5173");
        request.addHeader("Access-Control-Request-Method", "GET");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void githubWebhookDoesNotRequireToken() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/github/webhooks");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
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
        Mockito.when(userAccountMapper.selectById(1001L)).thenReturn(user());

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(request.getAttribute(AuthTokenFilter.AUTHENTICATED_USER_ATTRIBUTE))
            .isInstanceOf(AuthTokenService.AuthenticatedUser.class);
    }

    @Test
    void protectedApiRejectsTokenWhenUserIsNoLongerActive() throws ServletException, IOException {
        UserAccount user = user();
        user.setStatus("DISABLED");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reviews");
        request.addHeader("Authorization", "Bearer " + authTokenService.issueAccessToken(user()).token());
        MockHttpServletResponse response = new MockHttpServletResponse();
        Mockito.when(userAccountMapper.selectById(1001L)).thenReturn(user);

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    void protectedApiRejectsTokenWhenSessionVersionChanged() throws ServletException, IOException {
        UserAccount issuedUser = user();
        issuedUser.setSessionVersion(1);
        UserAccount currentUser = user();
        currentUser.setSessionVersion(2);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/config/github");
        request.addHeader("Authorization", "Bearer " + authTokenService.issueAccessToken(issuedUser).token());
        MockHttpServletResponse response = new MockHttpServletResponse();
        Mockito.when(userAccountMapper.selectById(1001L)).thenReturn(currentUser);

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    void protectedApiUsesCurrentDatabaseRoleForAuthenticatedUserAttribute() throws ServletException, IOException {
        UserAccount issuedUser = user();
        issuedUser.setRole("ADMIN");
        UserAccount currentUser = user();
        currentUser.setRole("VIEWER");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reviews");
        request.addHeader("Authorization", "Bearer " + authTokenService.issueAccessToken(issuedUser).token());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        Mockito.when(userAccountMapper.selectById(1001L)).thenReturn(currentUser);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(request.getAttribute(AuthTokenFilter.AUTHENTICATED_USER_ATTRIBUTE))
            .isInstanceOfSatisfying(AuthTokenService.AuthenticatedUser.class, authenticatedUser -> {
                assertThat(authenticatedUser.id()).isEqualTo(1001L);
                assertThat(authenticatedUser.username()).isEqualTo("admin");
                assertThat(authenticatedUser.role()).isEqualTo("VIEWER");
                assertThat(authenticatedUser.sessionVersion()).isZero();
            });
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
        user.setStatus("ACTIVE");
        return user;
    }
}
