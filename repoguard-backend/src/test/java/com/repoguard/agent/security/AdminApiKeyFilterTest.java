package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminApiKeyFilterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void protectedConfigEndpointRejectsMissingAdminKey() throws ServletException, IOException {
        AdminApiKeyFilter filter = new AdminApiKeyFilter(properties("secret-admin-key"), objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/config/review-policy");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    void protectedReviewWriteEndpointRejectsInvalidAdminKey() throws ServletException, IOException {
        AdminApiKeyFilter filter = new AdminApiKeyFilter(properties("secret-admin-key"), objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reviews/42/github-comments");
        request.addHeader("X-RepoGuard-Admin-Key", "wrong-key");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
    }

    @Test
    void protectedReviewWriteEndpointAllowsValidAdminKey() throws ServletException, IOException {
        AdminApiKeyFilter filter = new AdminApiKeyFilter(properties("secret-admin-key"), objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reviews/manual");
        request.addHeader("X-RepoGuard-Admin-Key", "secret-admin-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void readOnlyReviewEndpointDoesNotRequireAdminKey() throws ServletException, IOException {
        AdminApiKeyFilter filter = new AdminApiKeyFilter(properties("secret-admin-key"), objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reviews/42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void blankNonProductionKeyDisablesProtectionForLocalDevelopment() throws ServletException, IOException {
        AdminApiKeyFilter filter = new AdminApiKeyFilter(properties(" "), objectMapper);
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

    private AdminApiKeyProperties properties(String key) {
        AdminApiKeyProperties properties = new AdminApiKeyProperties();
        properties.setKey(key);
        return properties;
    }
}
