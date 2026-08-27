package com.repoguard.agent.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.mapper.TenantMembershipMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class TenantResolutionServiceTest {

    private final TenantMembershipMapper mapper = mock(TenantMembershipMapper.class);
    private final TenantResolutionService service = new TenantResolutionService(mapper);

    @Test
    void selectsDefaultMembershipAndItsTenantRole() {
        when(mapper.selectActiveMemberships(9L)).thenReturn(List.of(
            membership(2L, "alpha", "VIEWER", false),
            membership(3L, "beta", "ADMIN", true)
        ));

        TenantMembershipView resolved = service.resolve(9L, null, null);

        assertThat(resolved.tenantId()).isEqualTo(3L);
        assertThat(resolved.role()).isEqualTo("ADMIN");
    }

    @Test
    void explicitIdentityTenantCannotBeOverriddenByHeader() {
        when(mapper.selectActiveMemberships(9L)).thenReturn(List.of(
            membership(2L, "alpha", "VIEWER", false),
            membership(3L, "beta", "ADMIN", true)
        ));

        assertThat(service.resolve(9L, 2L, "beta").tenantId()).isEqualTo(2L);
    }

    @Test
    void selectsCaseInsensitiveRequestedTenant() {
        when(mapper.selectActiveMemberships(9L)).thenReturn(List.of(
            membership(2L, "alpha", "VIEWER", false),
            membership(3L, "beta", "ADMIN", false)
        ));

        assertThat(service.resolve(9L, null, " BETA ").tenantId()).isEqualTo(3L);
    }

    @Test
    void requiresSelectionWhenSeveralMembershipsHaveNoDefault() {
        when(mapper.selectActiveMemberships(9L)).thenReturn(List.of(
            membership(2L, "alpha", "VIEWER", false),
            membership(3L, "beta", "ADMIN", false)
        ));

        assertThatThrownBy(() -> service.resolve(9L, null, null))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST)
            );
    }

    @Test
    void rejectsTenantOutsideMemberships() {
        when(mapper.selectActiveMemberships(9L)).thenReturn(List.of(
            membership(2L, "alpha", "VIEWER", true)
        ));

        assertThatThrownBy(() -> service.resolve(9L, 3L, null))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN)
            );
    }

    @Test
    void platformApiKeyUsesDefaultTenantWithoutMembershipLookup() {
        TenantMembershipView resolved = service.resolve(0L, null, null);

        assertThat(resolved)
            .isEqualTo(new TenantMembershipView(1L, "default", "ADMIN", true));
    }

    private TenantMembershipView membership(long id, String key, String role, boolean defaultTenant) {
        return new TenantMembershipView(id, key, role, defaultTenant);
    }
}
