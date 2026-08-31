package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.tenancy.PlatformTenantScope;
import com.repoguard.agent.tenancy.TenantContext;
import com.repoguard.agent.tenancy.TenantProperties;
import net.sf.jsqlparser.expression.LongValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MybatisPlusTenantHandlerTest {

    private final TenantProperties properties = new TenantProperties();
    private final MybatisPlusConfig.TenantHandler handler =
        new MybatisPlusConfig.TenantHandler(properties);

    @AfterEach
    void contextsAreAlwaysCleared() {
        assertThat(TenantContext.currentTenantId()).isNull();
        assertThat(PlatformTenantScope.isActive()).isFalse();
    }

    @Test
    void disabledTenancyIgnoresTenantTables() {
        assertThat(handler.ignoreTable("review_task")).isTrue();
    }

    @Test
    void enabledTenancyFailsClosedWithoutTenantContext() {
        properties.setEnabled(true);

        assertThat(handler.ignoreTable("review_task")).isFalse();
        assertThatThrownBy(handler::getTenantId)
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Tenant-scoped SQL requires an active tenant context");
    }

    @Test
    void enabledTenancyStillIgnoresPlatformTables() {
        properties.setEnabled(true);

        assertThat(handler.ignoreTable("tenant_registry")).isTrue();
    }

    @Test
    void activeTenantProducesRequiredTenantPredicate() {
        properties.setEnabled(true);

        try (TenantContext.Scope _ = TenantContext.withTenant(23L)) {
            assertThat(handler.ignoreTable("`REVIEW_TASK`")).isFalse();
            assertThat(handler.getTenantId())
                .isInstanceOf(LongValue.class)
                .extracting(Object::toString)
                .isEqualTo("23");
        }
    }

    @Test
    void explicitPlatformScopeIsTheOnlyEnabledBypass() {
        properties.setEnabled(true);

        try (PlatformTenantScope _ = PlatformTenantScope.open("tenant_inventory")) {
            assertThat(handler.ignoreTable("review_task")).isTrue();
            assertThat(PlatformTenantScope.currentOperation()).isEqualTo("tenant_inventory");
        }
    }
}
