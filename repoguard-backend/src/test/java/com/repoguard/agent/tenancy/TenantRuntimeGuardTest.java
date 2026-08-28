package com.repoguard.agent.tenancy;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.repoguard.agent.mapper.TenantCatalogMapper;
import org.junit.jupiter.api.Test;

class TenantRuntimeGuardTest {

    private final TenantCatalogMapper mapper = mock(TenantCatalogMapper.class);
    private final TenantRuntimeGuard guard = new TenantRuntimeGuard(mapper);

    @Test
    void acceptsOnlyActiveTenant() {
        when(mapper.countActive(8L)).thenReturn(1);

        assertThatCode(() -> guard.requireActive(8L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> guard.requireActive(9L))
            .isInstanceOf(TenantInactiveException.class)
            .hasMessageContaining("9");
    }

    @Test
    void rejectsInvalidTenantIdWithoutDatabaseAccess() {
        assertThatThrownBy(() -> guard.requireActive(0L))
            .isInstanceOf(TenantInactiveException.class);
    }
}
