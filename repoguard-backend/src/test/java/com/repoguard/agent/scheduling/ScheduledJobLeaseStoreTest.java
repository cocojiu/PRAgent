package com.repoguard.agent.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.mapper.ScheduledJobLeaseMapper;
import com.repoguard.agent.tenancy.ScheduledJobLeaseProperties;
import com.repoguard.agent.tenancy.ScheduledJobLeaseStore;
import com.repoguard.agent.tenancy.TenantContext;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ScheduledJobLeaseStoreTest {

    private final ScheduledJobLeaseMapper mapper = mock(ScheduledJobLeaseMapper.class);
    private final ScheduledJobLeaseProperties properties = new ScheduledJobLeaseProperties();
    private final ScheduledJobLeaseStore store = new ScheduledJobLeaseStore(mapper, properties);

    @AfterEach
    void tenantContextIsCleared() {
        assertThat(TenantContext.currentTenantId()).isNull();
    }

    @Test
    void acquiresAndReleasesTenantScopedLease() {
        AtomicReference<String> acquiredOwner = captureOwnerOnAcquire();

        ScheduledJobLeaseStore.Lease lease;
        try (TenantContext.Scope _ = TenantContext.withTenant(7L)) {
            lease = store.tryAcquireCurrentTenant("dashboard_snapshot_recovery");
        }

        assertThat(lease).isNotNull();
        assertThat(lease.scopeKey()).isEqualTo("tenant:7:dashboard_snapshot_recovery");
        assertThat(lease.ownerId()).isEqualTo(acquiredOwner.get());
        store.release(lease);
        verify(mapper).release(
            eq("tenant:7:dashboard_snapshot_recovery"),
            eq(acquiredOwner.get())
        );
    }

    @Test
    void returnsNullWhenAnotherReplicaOwnsUnexpiredLease() {
        when(mapper.acquireOrCreate(
            anyString(),
            any(),
            anyString(),
            anyString(),
            anyLong()
        )).thenReturn(2);
        when(mapper.selectOwner("tenant:8:review_execution_recovery")).thenReturn("other-replica");

        try (TenantContext.Scope _ = TenantContext.withTenant(8L)) {
            assertThat(store.tryAcquireCurrentTenant("review_execution_recovery")).isNull();
        }
    }

    @Test
    void globalLeaseRequiresEmptyTenantContext() {
        AtomicReference<String> acquiredOwner = captureOwnerOnAcquire();

        ScheduledJobLeaseStore.Lease lease = store.tryAcquireGlobal("global_operational_data_retention");

        assertThat(lease.scopeKey()).isEqualTo("global:global_operational_data_retention");
        assertThat(lease.ownerId()).isEqualTo(acquiredOwner.get());
        try (TenantContext.Scope _ = TenantContext.withTenant(3L)) {
            assertThatThrownBy(() -> store.tryAcquireGlobal("global_operational_data_retention"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty tenant context");
        }
    }

    @Test
    void acquisitionSqlAllowsOnlyExpiredLeaseTakeover() throws NoSuchMethodException {
        Insert insert = ScheduledJobLeaseMapper.class.getMethod(
            "acquireOrCreate",
            String.class,
            Long.class,
            String.class,
            String.class,
            long.class
        ).getAnnotation(Insert.class);

        assertThat(String.join("\n", insert.value()).toLowerCase())
            .contains("on duplicate key update")
            .contains("date_add(current_timestamp(6), interval #{leaseseconds} second)")
            .contains("locked_until <= values(updated_at)")
            .contains("owner_id = if(");
    }

    @Test
    void rejectsUnsafeJobNameBeforeDatabaseAccess() {
        assertThatThrownBy(() -> store.tryAcquireGlobal("bad job/name"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("jobName must match");
    }

    private AtomicReference<String> captureOwnerOnAcquire() {
        AtomicReference<String> acquiredOwner = new AtomicReference<>();
        when(mapper.acquireOrCreate(
            anyString(),
            any(),
            anyString(),
            anyString(),
            anyLong()
        )).thenAnswer(invocation -> {
            acquiredOwner.set(invocation.getArgument(3));
            return 1;
        });
        when(mapper.selectOwner(anyString())).thenAnswer(invocation -> acquiredOwner.get());
        return acquiredOwner;
    }
}
