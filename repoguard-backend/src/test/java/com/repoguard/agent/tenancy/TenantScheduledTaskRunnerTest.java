package com.repoguard.agent.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.repoguard.agent.mapper.TenantCatalogMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantScheduledTaskRunnerTest {

    private final TenantCatalogMapper tenantCatalogMapper = mock(TenantCatalogMapper.class);
    private final TenantProperties properties = new TenantProperties();
    private final ScheduledJobLeaseGuardFactory leaseGuardFactory = mock(ScheduledJobLeaseGuardFactory.class);
    private final TenantScheduledTaskRunner runner = new TenantScheduledTaskRunner(
        tenantCatalogMapper,
        properties,
        leaseGuardFactory
    );

    TenantScheduledTaskRunnerTest() {
        when(leaseGuardFactory.tryAcquireCurrentTenant(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(invocation -> guard());
        when(leaseGuardFactory.tryAcquireGlobal(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(invocation -> guard());
    }

    @AfterEach
    void tenantContextIsAlwaysCleared() {
        assertThat(TenantContext.currentTenantId()).isNull();
    }

    @Test
    void disabledTenancyRunsOnceAsDefaultTenant() {
        List<Long> observed = new ArrayList<>();

        TenantScheduledTaskRunner.TenantRunSummary summary = runner.runForEachActiveTenant(
            "legacy_mode",
            () -> observed.add(TenantContext.currentTenantId())
        );

        assertThat(observed).containsExactly(TenantContext.DEFAULT_TENANT_ID);
        assertThat(summary).isEqualTo(new TenantScheduledTaskRunner.TenantRunSummary(1, 1, 0, 0));
        verifyNoInteractions(tenantCatalogMapper);
    }

    @Test
    void enabledTenancyPagesActiveIdsInOrder() {
        properties.setEnabled(true);
        when(tenantCatalogMapper.selectActiveTenantIdsAfter(0L, TenantScheduledTaskRunner.ACTIVE_TENANT_PAGE_SIZE))
            .thenReturn(List.of(2L, 5L));
        when(tenantCatalogMapper.selectActiveTenantIdsAfter(5L, TenantScheduledTaskRunner.ACTIVE_TENANT_PAGE_SIZE))
            .thenReturn(List.of(9L));
        when(tenantCatalogMapper.selectActiveTenantIdsAfter(9L, TenantScheduledTaskRunner.ACTIVE_TENANT_PAGE_SIZE))
            .thenReturn(List.of());
        List<Long> observed = new ArrayList<>();

        TenantScheduledTaskRunner.TenantRunSummary summary = runner.runForEachActiveTenant(
            "paged",
            () -> observed.add(TenantContext.currentTenantId())
        );

        assertThat(observed).containsExactly(2L, 5L, 9L);
        assertThat(summary).isEqualTo(new TenantScheduledTaskRunner.TenantRunSummary(3, 3, 0, 0));
    }

    @Test
    void oneTenantFailureDoesNotBlockFollowingTenants() {
        properties.setEnabled(true);
        when(tenantCatalogMapper.selectActiveTenantIdsAfter(0L, TenantScheduledTaskRunner.ACTIVE_TENANT_PAGE_SIZE))
            .thenReturn(List.of(2L, 3L, 4L));
        when(tenantCatalogMapper.selectActiveTenantIdsAfter(4L, TenantScheduledTaskRunner.ACTIVE_TENANT_PAGE_SIZE))
            .thenReturn(List.of());
        List<Long> observed = new ArrayList<>();

        TenantScheduledTaskRunner.TenantRunSummary summary = runner.runForEachActiveTenant("isolated_failure", () -> {
            Long tenantId = TenantContext.currentTenantId();
            observed.add(tenantId);
            if (tenantId == 3L) {
                throw new IllegalStateException("tenant-specific failure");
            }
        });

        assertThat(observed).containsExactly(2L, 3L, 4L);
        assertThat(summary).isEqualTo(new TenantScheduledTaskRunner.TenantRunSummary(3, 2, 1, 0));
    }

    @Test
    void emptyCatalogReturnsZeroSummary() {
        properties.setEnabled(true);
        when(tenantCatalogMapper.selectActiveTenantIdsAfter(0L, TenantScheduledTaskRunner.ACTIVE_TENANT_PAGE_SIZE))
            .thenReturn(List.of());

        assertThat(runner.runForEachActiveTenant("empty", () -> { }))
            .isEqualTo(new TenantScheduledTaskRunner.TenantRunSummary(0, 0, 0, 0));
    }

    @Test
    void leaseOwnedByAnotherReplicaSkipsOnlyThatTenant() {
        properties.setEnabled(true);
        when(tenantCatalogMapper.selectActiveTenantIdsAfter(0L, TenantScheduledTaskRunner.ACTIVE_TENANT_PAGE_SIZE))
            .thenReturn(List.of(2L, 3L));
        when(tenantCatalogMapper.selectActiveTenantIdsAfter(3L, TenantScheduledTaskRunner.ACTIVE_TENANT_PAGE_SIZE))
            .thenReturn(List.of());
        when(leaseGuardFactory.tryAcquireCurrentTenant("leased"))
            .thenAnswer(invocation -> TenantContext.currentTenantId() == 2L ? null : guard());
        List<Long> observed = new ArrayList<>();

        TenantScheduledTaskRunner.TenantRunSummary summary = runner.runForEachActiveTenant(
            "leased",
            () -> observed.add(TenantContext.currentTenantId())
        );

        assertThat(observed).containsExactly(3L);
        assertThat(summary).isEqualTo(new TenantScheduledTaskRunner.TenantRunSummary(2, 1, 0, 1));
    }

    @Test
    void globalTaskRunsOnlyWhenLeaseIsAcquired() {
        List<String> observed = new ArrayList<>();

        assertThat(runner.runGlobal("global_job", () -> observed.add("first"))).isTrue();
        when(leaseGuardFactory.tryAcquireGlobal("global_job")).thenReturn(null);
        assertThat(runner.runGlobal("global_job", () -> observed.add("second"))).isFalse();

        assertThat(observed).containsExactly("first");
    }

    @Test
    void rejectsContaminatedSchedulerThreadWithoutChangingItsContext() {
        try (TenantContext.Scope _ = TenantContext.withTenant(17L)) {
            assertThatThrownBy(() -> runner.runForEachActiveTenant("contaminated", () -> { }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty tenant context");
            assertThat(TenantContext.currentTenantId()).isEqualTo(17L);
        }
    }

    @Test
    void rejectsNonIncreasingCatalogIdsAndStillClearsContext() {
        properties.setEnabled(true);
        when(tenantCatalogMapper.selectActiveTenantIdsAfter(0L, TenantScheduledTaskRunner.ACTIVE_TENANT_PAGE_SIZE))
            .thenReturn(List.of(2L, 2L));

        assertThatThrownBy(() -> runner.runForEachActiveTenant("bad_catalog", () -> { }))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("non-increasing");
    }

    private ScheduledJobLeaseGuard guard() {
        return mock(ScheduledJobLeaseGuard.class);
    }
}
