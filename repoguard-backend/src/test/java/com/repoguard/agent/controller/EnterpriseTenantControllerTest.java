package com.repoguard.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.repoguard.agent.authentication.AuthenticatedPrincipal;
import com.repoguard.agent.authentication.RequestAuthenticationAttributes;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.EnterpriseTenantCreateRequest;
import com.repoguard.agent.dto.EnterpriseTenantDto;
import com.repoguard.agent.tenancy.EnterpriseTenantAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class EnterpriseTenantControllerTest {

    private final EnterpriseTenantAdminService service = mock(EnterpriseTenantAdminService.class);
    private final EnterpriseTenantController controller = new EnterpriseTenantController(service);

    @Test
    void platformAdminApiKeyCanCreateTenant() {
        EnterpriseTenantCreateRequest request = new EnterpriseTenantCreateRequest("acme", "Acme", 12L);
        EnterpriseTenantDto created = new EnterpriseTenantDto(8L, "acme", "Acme");
        when(service.create(request)).thenReturn(created);

        var response = controller.create(request, requestWithPrincipal(
            new AuthenticatedPrincipal(0L, "admin-api-key", "ADMIN", Long.MAX_VALUE)
        ));

        assertThat(response.data()).isEqualTo(created);
        verify(service).create(request);
    }

    @Test
    void tenantAdminCannotUsePlatformControlPlane() {
        EnterpriseTenantCreateRequest request = new EnterpriseTenantCreateRequest("acme", "Acme", 12L);

        assertThatThrownBy(() -> controller.create(request, requestWithPrincipal(
            new AuthenticatedPrincipal(12L, "tenant-admin", "ADMIN", Long.MAX_VALUE, 0, 8L)
        )))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN)
            );
        verifyNoInteractions(service);
    }

    private MockHttpServletRequest requestWithPrincipal(AuthenticatedPrincipal principal) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL, principal);
        return request;
    }
}
