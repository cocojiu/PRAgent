package com.repoguard.agent.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.authentication.AuthenticatedPrincipal;
import com.repoguard.agent.authentication.RequestAuthenticationAttributes;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TenantContextFilterTest {

    private final TenantProperties properties = enabledProperties();
    private final TenantResolutionService resolutionService = mock(TenantResolutionService.class);
    private final TenantContextFilter filter = new TenantContextFilter(
        properties,
        resolutionService,
        new ObjectMapper()
    );

    @AfterEach
    void tenantContextDoesNotLeakBetweenTests() {
        assertThat(TenantContext.currentTenantId()).isNull();
    }

    @Test
    void scopesDownstreamRequestAndRewritesRoleFromMembership() throws Exception {
        MockHttpServletRequest request = authenticatedRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(resolutionService.resolve(9L, null, "tenant-b"))
            .thenReturn(new TenantMembershipView(2L, "tenant-b", "VIEWER", false));
        FilterChain chain = (candidateRequest, candidateResponse) -> {
            assertThat(TenantContext.currentTenantId()).isEqualTo(2L);
            Object principal = candidateRequest.getAttribute(
                RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL
            );
            assertThat(principal).isEqualTo(
                new AuthenticatedPrincipal(9L, "alice", "VIEWER", 1000L, 4, 2L)
            );
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void returnsForbiddenWithoutCallingDownstreamChain() throws Exception {
        MockHttpServletRequest request = authenticatedRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        when(resolutionService.resolve(9L, null, "tenant-b"))
            .thenThrow(new BusinessException(ErrorCode.FORBIDDEN, "denied"));

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"success\":false", "\"message\":\"denied\"");
        verify(resolutionService).resolve(9L, null, "tenant-b");
    }

    @Test
    void anonymousRequestPassesWithoutTenantContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (candidateRequest, candidateResponse) ->
            assertThat(TenantContext.currentTenantId()).isNull();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void controlPlaneTenantRequestKeepsGlobalPrincipalWithoutResolvingMembership() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/enterprise/tenants");
        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(
            100L, "platform", "PLATFORM_ADMIN", 1000L, 4
        );
        request.setAttribute(RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL, principal);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (candidateRequest, candidateResponse) -> {
            assertThat(TenantContext.currentTenantId()).isNull();
            assertThat(candidateRequest.getAttribute(RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL))
                .isEqualTo(principal);
        };

        filter.doFilter(request, response, chain);

        verifyNoInteractions(resolutionService);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private MockHttpServletRequest authenticatedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-RepoGuard-Tenant", "tenant-b");
        request.setAttribute(
            RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL,
            new AuthenticatedPrincipal(9L, "alice", "ADMIN", 1000L, 4)
        );
        return request;
    }

    private TenantProperties enabledProperties() {
        TenantProperties value = new TenantProperties();
        value.setEnabled(true);
        return value;
    }
}
