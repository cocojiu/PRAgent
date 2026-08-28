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
import com.repoguard.agent.dto.EnterpriseTenantQuotaDto;
import com.repoguard.agent.dto.EnterpriseTenantQuotaRequest;
import com.repoguard.agent.tenancy.TenantQuotaService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class EnterpriseTenantQuotaControllerTest {

    private final TenantQuotaService service = mock(TenantQuotaService.class);
    private final EnterpriseTenantQuotaController controller = new EnterpriseTenantQuotaController(service);

    @Test
    void platformAdminCanReadAndUpdateQuota() {
        EnterpriseTenantQuotaDto quota = quota(8L, 2L, 1000, 12);
        EnterpriseTenantQuotaRequest request = new EnterpriseTenantQuotaRequest(2L, 1500);
        when(service.get("acme")).thenReturn(quota);
        when(service.update("acme", request)).thenReturn(quota);
        MockHttpServletRequest httpRequest = requestWithPrincipal(
            new AuthenticatedPrincipal(0L, "admin-api-key", "ADMIN", Long.MAX_VALUE)
        );

        assertThat(controller.get("acme", httpRequest).data()).isEqualTo(quota);
        assertThat(controller.update("acme", request, httpRequest).data()).isEqualTo(quota);
        verify(service).get("acme");
        verify(service).update("acme", request);
    }

    @Test
    void tenantAdminCannotReadPlatformQuota() {
        assertThatThrownBy(() -> controller.get(
            "acme",
            requestWithPrincipal(new AuthenticatedPrincipal(12L, "tenant-admin", "ADMIN", Long.MAX_VALUE, 0, 8L))
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN)
        );
        verifyNoInteractions(service);
    }

    private EnterpriseTenantQuotaDto quota(long tenantId, long version, int limit, int used) {
        return new EnterpriseTenantQuotaDto(
            tenantId,
            "acme",
            version,
            limit,
            used,
            LocalDate.of(2026, 8, 28),
            LocalDateTime.of(2026, 8, 28, 12, 0)
        );
    }

    private MockHttpServletRequest requestWithPrincipal(AuthenticatedPrincipal principal) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL, principal);
        return request;
    }
}
