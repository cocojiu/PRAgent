package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.authentication.AuthenticatedPrincipal;
import com.repoguard.agent.authentication.RequestAuthenticationAttributes;
import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.mapper.UserAccountMapper;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
    private final AuthAccountCache authAccountCache = new AuthAccountCache(userAccountMapper);
    private final AuthTokenFilter filter = new AuthTokenFilter(authTokenService, authAccountCache, new ObjectMapper());

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
    void authEndpointWithUnmappedMethodStillRequiresToken() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
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
        assertThat(request.getAttribute(RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL))
            .isInstanceOf(AuthenticatedPrincipal.class);
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
    void pathParameterVariantStillRequiresToken() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1;x=1/reviews");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    void percentEncodedVariantStillRequiresToken() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/%72eviews");
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
            RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL,
            new AuthenticatedPrincipal(0L, "admin-api-key", "ADMIN", Long.MAX_VALUE)
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void protectedApiRejectsLegacyFourFieldToken() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reviews");
        request.addHeader("Authorization", "Bearer " + legacyFourFieldToken());
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
        Mockito.verify(userAccountMapper, Mockito.never()).selectById(1001L);
    }

    @Test
    void protectedApiAllowsTokenSignedWithPreviousSecretAfterRotation() throws ServletException, IOException {
        String token = authTokenService.issueAccessToken(user()).token();
        authProperties.setTokenSecret("rotated-secret");
        authProperties.setTokenSecretId("k2");
        authProperties.setTokenSecretPrevious("test-secret");
        authProperties.setTokenSecretPreviousId("k1");
        Mockito.when(userAccountMapper.selectById(1001L)).thenReturn(user());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reviews");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(request.getAttribute(RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL))
            .isInstanceOf(AuthenticatedPrincipal.class);
    }

    @Test
    void protectedApiRejectsTokenSignedWithRetiredSecret() throws ServletException, IOException {
        String token = authTokenService.issueAccessToken(user()).token();
        authProperties.setTokenSecret("rotated-secret");
        authProperties.setTokenSecretId("k2");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reviews");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        Mockito.verify(userAccountMapper, Mockito.never()).selectById(1001L);
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
        assertThat(request.getAttribute(RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL))
            .isInstanceOf(AuthenticatedPrincipal.class);
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
        assertThat(request.getAttribute(RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL))
            .isInstanceOfSatisfying(AuthenticatedPrincipal.class, authenticatedUser -> {
                assertThat(authenticatedUser.id()).isEqualTo(1001L);
                assertThat(authenticatedUser.username()).isEqualTo("admin");
                assertThat(authenticatedUser.role()).isEqualTo("VIEWER");
                assertThat(authenticatedUser.sessionVersion()).isZero();
            });
    }

    @Test
    void repeatedRequestsWithinCacheTtlQueryDatabaseOnce() throws ServletException, IOException {
        String token = authTokenService.issueAccessToken(user()).token();
        Mockito.when(userAccountMapper.selectById(1001L)).thenReturn(user());

        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reviews");
            request.addHeader("Authorization", "Bearer " + token);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(200);
        }

        Mockito.verify(userAccountMapper, Mockito.times(1)).selectById(1001L);
    }

    @Test
    void cacheInvalidationForcesImmediateDatabaseReload() throws ServletException, IOException {
        String token = authTokenService.issueAccessToken(user()).token();
        UserAccount disabledUser = user();
        disabledUser.setStatus("DISABLED");
        Mockito.when(userAccountMapper.selectById(1001L)).thenReturn(user(), disabledUser);

        MockHttpServletRequest firstRequest = new MockHttpServletRequest("GET", "/api/v1/reviews");
        firstRequest.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(firstRequest, firstResponse, new MockFilterChain());
        assertThat(firstResponse.getStatus()).isEqualTo(200);

        authAccountCache.invalidate(1001L);

        MockHttpServletRequest secondRequest = new MockHttpServletRequest("GET", "/api/v1/reviews");
        secondRequest.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(secondRequest, secondResponse, new MockFilterChain());

        assertThat(secondResponse.getStatus()).isEqualTo(401);
        Mockito.verify(userAccountMapper, Mockito.times(2)).selectById(1001L);
    }

    @Test
    void missingUserLookupIsCachedWithinTtl() throws ServletException, IOException {
        String token = authTokenService.issueAccessToken(user()).token();
        Mockito.when(userAccountMapper.selectById(1001L)).thenReturn(null);

        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reviews");
            request.addHeader("Authorization", "Bearer " + token);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(401);
        }

        Mockito.verify(userAccountMapper, Mockito.times(1)).selectById(1001L);
    }

    private AuthProperties properties() {
        AuthProperties properties = new AuthProperties();
        properties.setTokenSecret("test-secret");
        return properties;
    }

    private String legacyFourFieldToken() {
        String payload = "1001:admin:ADMIN:" + (Instant.now().getEpochSecond() + 900);
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return encodedPayload + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Test signing is not available", ex);
        }
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
