package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.authentication.AuthenticatedPrincipal;
import com.repoguard.agent.authentication.RequestAuthenticationAttributes;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminApiKeyFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AuthTokenService authTokenService = new AuthTokenService(authProperties());

    @Test
    void protectedConfigEndpointRejectsMissingAdminKey() throws ServletException, IOException {
        AdminApiKeyFilter filter = filter("secret-admin-key");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/config/review-policy");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    void corsPreflightDoesNotRequireAdminKey() throws ServletException, IOException {
        AdminApiKeyFilter filter = filter("secret-admin-key");
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/config/review-policy");
        request.addHeader("Origin", "http://localhost:5173");
        request.addHeader("Access-Control-Request-Method", "GET");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void protectedReviewWriteEndpointRejectsInvalidAdminKey() throws ServletException, IOException {
        AdminApiKeyFilter filter = filter("secret-admin-key");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reviews/42/github-comments");
        request.addHeader("X-RepoGuard-Admin-Key", "wrong-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
        assertThat(response.getContentAsString()).contains("invalid or missing");
    }

    @Test
    void bearerAuthenticatedRequestBypassesAdminKeyAndUsesRbacLater() throws ServletException, IOException {
        AdminApiKeyFilter filter = filter("secret-admin-key");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/config/review-policy");
        request.addHeader("Authorization", "Bearer " + authTokenService.issueAccessToken(1001L, "admin", "ADMIN", 0).token());
        request.addHeader("X-RepoGuard-Admin-Key", "wrong-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void garbageBearerTokenDoesNotBypassAdminKey() throws ServletException, IOException {
        AdminApiKeyFilter filter = filter("secret-admin-key");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/config/review-policy");
        request.addHeader("Authorization", "Bearer x");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    void tamperedBearerTokenDoesNotBypassAdminKey() throws ServletException, IOException {
        AdminApiKeyFilter filter = filter("secret-admin-key");
        String token = authTokenService.issueAccessToken(1001L, "admin", "ADMIN", 0).token();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users");
        request.addHeader("Authorization", "Bearer " + token + "x");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    void validAdminKeyWithUnverifiableBearerStillAuthenticates() throws ServletException, IOException {
        AdminApiKeyFilter filter = filter("secret-admin-key");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reviews/manual");
        request.addHeader("Authorization", "Bearer x");
        request.addHeader("X-RepoGuard-Admin-Key", "secret-admin-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(request.getAttribute(RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL))
            .isEqualTo(new AuthenticatedPrincipal(0L, "admin-api-key", "ADMIN", Long.MAX_VALUE));
    }

    @Test
    void pathParameterVariantStillRequiresAdminKey() throws ServletException, IOException {
        AdminApiKeyFilter filter = filter("secret-admin-key");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reviews/manual;x=1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    void percentEncodedVariantStillRequiresAdminKey() throws ServletException, IOException {
        AdminApiKeyFilter filter = filter("secret-admin-key");
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/v1/%75sers/1/role");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    void duplicateSlashVariantStillRequiresAdminKey() throws ServletException, IOException {
        AdminApiKeyFilter filter = filter("secret-admin-key");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1//users");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    void protectedMessageQueueRequeueEndpointRejectsMissingAdminKey() throws ServletException, IOException {
        AdminApiKeyFilter filter = filter("secret-admin-key");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/message-queue/tasks/42/requeue");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    void protectedMessageQueueHealthEndpointRejectsMissingAdminKey() throws ServletException, IOException {
        AdminApiKeyFilter filter = filter("secret-admin-key");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/message-queue/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    void protectedUserManagementEndpointRejectsMissingAdminKey() throws ServletException, IOException {
        AdminApiKeyFilter filter = filter("secret-admin-key");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    void protectedNotificationRetryEndpointRejectsMissingAdminKey() throws ServletException, IOException {
        AdminApiKeyFilter filter = filter("secret-admin-key");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/notification-events/42/retry");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    void protectedNotificationReadEndpointRejectsMissingAdminKey() throws ServletException, IOException {
        AdminApiKeyFilter filter = filter("secret-admin-key");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/notification-deliveries");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    void protectedReviewWriteEndpointAllowsValidAdminKey() throws ServletException, IOException {
        AdminApiKeyFilter filter = filter("secret-admin-key");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reviews/manual");
        request.addHeader("X-RepoGuard-Admin-Key", "secret-admin-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(request.getAttribute(RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL))
            .isEqualTo(new AuthenticatedPrincipal(0L, "admin-api-key", "ADMIN", Long.MAX_VALUE));
    }

    @Test
    void readOnlyReviewEndpointDoesNotRequireAdminKey() throws ServletException, IOException {
        AdminApiKeyFilter filter = filter("secret-admin-key");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reviews/42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void blankNonProductionKeyDisablesProtectionForLocalDevelopment() throws ServletException, IOException {
        AdminApiKeyFilter filter = filter(" ");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/config/review-policy");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void productionProfileRequiresConfiguredAdminKey() {
        AdminApiKeyProperties properties = properties(" ");

        assertThatThrownBy(() -> properties.validateForProfiles(new String[] {"prod"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("admin-api-key.key");
    }

    @Test
    void stagingProfileRequiresConfiguredAdminKey() {
        AdminApiKeyProperties properties = properties(" ");

        assertThatThrownBy(() -> properties.validateForProfiles(new String[] {"staging"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("admin-api-key.key");
    }

    @Test
    void productionProfileRejectsShortOrPlaceholderAdminKeys() {
        AdminApiKeyProperties shortKey = properties("short-admin-key");
        AdminApiKeyProperties placeholder = properties("change-me-admin-api-key-with-more-than-32-characters");

        assertThatThrownBy(() -> shortKey.validateForProfiles(new String[] {"prod"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("at least 32");
        assertThatThrownBy(() -> placeholder.validateForProfiles(new String[] {"prod"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("non-placeholder");
    }

    @Test
    void failedAttemptLimitIsFailClosedAndAuditedWithoutCredentialDetails()
        throws ServletException, IOException {
        AdminApiKeyAttemptLimiter attemptLimiter = mock(AdminApiKeyAttemptLimiter.class);
        AdminApiKeyFailureAuditRecorder auditRecorder = mock(AdminApiKeyFailureAuditRecorder.class);
        when(attemptLimiter.recordFailureAllowed(org.mockito.ArgumentMatchers.any())).thenReturn(false);
        AdminApiKeyFilter filter = new AdminApiKeyFilter(
            properties("secret-admin-key"),
            authTokenService,
            objectMapper,
            attemptLimiter,
            auditRecorder
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users");
        request.addHeader("X-RepoGuard-Admin-Key", "credential-that-must-not-be-audited");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("\"code\":\"TOO_MANY_REQUESTS\"");
        assertThat(response.getContentAsString()).doesNotContain("credential-that-must-not-be-audited");
        verify(auditRecorder).record(request, "ADMIN_API_KEY_RATE_LIMITED");
    }

    private AdminApiKeyFilter filter(String key) {
        return new AdminApiKeyFilter(properties(key), authTokenService, objectMapper);
    }

    private AdminApiKeyProperties properties(String key) {
        AdminApiKeyProperties properties = new AdminApiKeyProperties();
        properties.setKey(key);
        return properties;
    }

    private AuthProperties authProperties() {
        AuthProperties properties = new AuthProperties();
        properties.setTokenSecret("test-secret");
        return properties;
    }
}
