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
import com.repoguard.agent.dto.EnterpriseIdentityBindingRequest;
import com.repoguard.agent.dto.EnterpriseTenantCreateRequest;
import com.repoguard.agent.dto.EnterpriseTenantDto;
import com.repoguard.agent.dto.EnterpriseTenantMembershipRequest;
import com.repoguard.agent.dto.EnterpriseTenantRepositoryRequest;
import com.repoguard.agent.dto.EnterpriseTenantStatusRequest;
import com.repoguard.agent.dto.PageResponse;
import java.util.List;
import java.time.LocalDateTime;
import com.repoguard.agent.tenancy.EnterpriseTenantAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class EnterpriseTenantControllerTest {

    private final EnterpriseTenantAdminService service = mock(EnterpriseTenantAdminService.class);
    private final EnterpriseTenantController controller = new EnterpriseTenantController(service);

    @Test
    void platformAdminApiKeyCanCreateTenant() {
        EnterpriseTenantCreateRequest request = new EnterpriseTenantCreateRequest("acme", "Acme", 12L);
        LocalDateTime now = LocalDateTime.parse("2026-08-28T12:00:00");
        EnterpriseTenantDto created = new EnterpriseTenantDto(
            8L, "acme", "Acme", "ACTIVE", 1L, null, now, now, now
        );
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

    @Test
    void platformAdminCanSuspendTenantWithExpectedVersion() {
        EnterpriseTenantStatusRequest request =
            new EnterpriseTenantStatusRequest("ACTIVE", "SUSPENDED", 3L, "maintenance");
        LocalDateTime now = LocalDateTime.parse("2026-08-28T12:00:00");
        EnterpriseTenantDto suspended = new EnterpriseTenantDto(
            8L, "acme", "Acme", "SUSPENDED", 4L, "maintenance", now, now, now
        );
        when(service.updateStatus("acme", request)).thenReturn(suspended);

        var response = controller.updateStatus(
            "acme",
            request,
            requestWithPrincipal(new AuthenticatedPrincipal(0L, "admin-api-key", "ADMIN", Long.MAX_VALUE))
        );

        assertThat(response.data()).isEqualTo(suspended);
        verify(service).updateStatus("acme", request);
    }

    @Test
    void platformAdminCanListTenants() {
        LocalDateTime now = LocalDateTime.parse("2026-08-28T12:00:00");
        EnterpriseTenantDto tenant = new EnterpriseTenantDto(
            8L, "acme", "Acme", "ACTIVE", 1L, null, now, now, now
        );
        PageResponse<EnterpriseTenantDto> page = new PageResponse<>(List.of(tenant), 1L);
        when(service.list(1, 20, "ACTIVE")).thenReturn(page);

        var response = controller.list(
            1,
            20,
            "ACTIVE",
            requestWithPrincipal(new AuthenticatedPrincipal(0L, "admin-api-key", "ADMIN", Long.MAX_VALUE))
        );

        assertThat(response.data()).isEqualTo(page);
        verify(service).list(1, 20, "ACTIVE");
    }

    @Test
    void platformAdminCanReadTenantDetail() {
        LocalDateTime now = LocalDateTime.parse("2026-08-28T12:00:00");
        EnterpriseTenantDto tenant = new EnterpriseTenantDto(
            8L, "acme", "Acme", "SUSPENDED", 2L, "maintenance", now, now, now
        );
        when(service.get("acme")).thenReturn(tenant);

        var response = controller.get(
            "acme",
            requestWithPrincipal(new AuthenticatedPrincipal(0L, "admin-api-key", "ADMIN", Long.MAX_VALUE))
        );

        assertThat(response.data()).isEqualTo(tenant);
        verify(service).get("acme");
    }

    @Test
    void platformAdminCanUpsertMembership() {
        EnterpriseTenantMembershipRequest request =
            new EnterpriseTenantMembershipRequest(12L, "VIEWER", true);

        var response = controller.putMembership(
            "acme",
            request,
            requestWithPrincipal(new AuthenticatedPrincipal(0L, "admin-api-key", "ADMIN", Long.MAX_VALUE))
        );

        assertThat(response.data()).isNull();
        verify(service).putMembership("acme", request);
    }

    @Test
    void platformAdminCanUpsertRepository() {
        EnterpriseTenantRepositoryRequest request =
            new EnterpriseTenantRepositoryRequest("openai", "repoguard", 77L);

        var response = controller.putRepository(
            "acme",
            request,
            requestWithPrincipal(new AuthenticatedPrincipal(0L, "admin-api-key", "ADMIN", Long.MAX_VALUE))
        );

        assertThat(response.data()).isNull();
        verify(service).putRepository("acme", request);
    }

    @Test
    void platformAdminCanUpsertIdentity() {
        EnterpriseIdentityBindingRequest request =
            new EnterpriseIdentityBindingRequest(12L, "https://identity.example.com", "subject");

        var response = controller.putIdentity(
            "acme",
            request,
            requestWithPrincipal(new AuthenticatedPrincipal(0L, "admin-api-key", "ADMIN", Long.MAX_VALUE))
        );

        assertThat(response.data()).isNull();
        verify(service).putIdentity("acme", request);
    }

    private MockHttpServletRequest requestWithPrincipal(AuthenticatedPrincipal principal) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL, principal);
        return request;
    }
}
