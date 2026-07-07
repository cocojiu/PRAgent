package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SecurityHeadersFilterTest {

    private final SecurityHeadersFilter filter = new SecurityHeadersFilter();

    @Test
    void addsSecurityHeadersToResponses() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reviews");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(SecurityHeadersFilter.CONTENT_SECURITY_POLICY))
            .isEqualTo(SecurityHeadersFilter.CONTENT_SECURITY_POLICY_VALUE);
        assertThat(response.getHeader(SecurityHeadersFilter.X_CONTENT_TYPE_OPTIONS)).isEqualTo("nosniff");
        assertThat(response.getHeader(SecurityHeadersFilter.X_FRAME_OPTIONS)).isEqualTo("DENY");
        assertThat(response.getHeader(SecurityHeadersFilter.REFERRER_POLICY)).isEqualTo("no-referrer");
        assertThat(response.getHeader(SecurityHeadersFilter.PERMISSIONS_POLICY))
            .isEqualTo(SecurityHeadersFilter.PERMISSIONS_POLICY_VALUE);
        assertThat(response.getHeader(SecurityHeadersFilter.STRICT_TRANSPORT_SECURITY)).isNull();
    }

    @Test
    void addsStrictTransportSecurityForForwardedHttpsRequests() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/repoguard/overview");
        request.addHeader("X-Forwarded-Proto", "https");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(SecurityHeadersFilter.STRICT_TRANSPORT_SECURITY))
            .isEqualTo(SecurityHeadersFilter.STRICT_TRANSPORT_SECURITY_VALUE);
    }

    @Test
    void keepsExistingGatewayHeaders() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/reviews");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader(SecurityHeadersFilter.CONTENT_SECURITY_POLICY, "default-src 'none'");
        response.setHeader(SecurityHeadersFilter.X_FRAME_OPTIONS, "SAMEORIGIN");

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(SecurityHeadersFilter.CONTENT_SECURITY_POLICY)).isEqualTo("default-src 'none'");
        assertThat(response.getHeader(SecurityHeadersFilter.X_FRAME_OPTIONS)).isEqualTo("SAMEORIGIN");
        assertThat(response.getHeader(SecurityHeadersFilter.X_CONTENT_TYPE_OPTIONS)).isEqualTo("nosniff");
    }
}
