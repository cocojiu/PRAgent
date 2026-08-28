package com.repoguard.agent.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ScheduledJobLeaseInfrastructureTest {

    @Test
    void propertiesExposeAValidatedHeartbeatWindowAndBoundedThreadCount() {
        ScheduledJobLeaseProperties properties = new ScheduledJobLeaseProperties();

        properties.setLeaseSeconds(120L);
        properties.setHeartbeatSeconds(30L);
        properties.setHeartbeatThreads(3);

        assertThat(properties.getLeaseSeconds()).isEqualTo(120L);
        assertThat(properties.getHeartbeatSeconds()).isEqualTo(30L);
        assertThat(properties.getHeartbeatThreads()).isEqualTo(3);
        assertThat(properties.isHeartbeatWithinLease()).isTrue();
        properties.setHeartbeatSeconds(120L);
        assertThat(properties.isHeartbeatWithinLease()).isFalse();
    }

    @Test
    void guardFactoryCreatesTenantAndGlobalGuardsAndPreservesContentionSignal() {
        ScheduledJobLeaseStore store = mock(ScheduledJobLeaseStore.class);
        ScheduledJobLeaseProperties properties = new ScheduledJobLeaseProperties();
        properties.setHeartbeatSeconds(5L);
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
        doReturn(heartbeat).when(executor)
            .scheduleWithFixedDelay(any(), anyLong(), anyLong(), eq(TimeUnit.SECONDS));
        ScheduledJobLeaseGuardFactory factory = new ScheduledJobLeaseGuardFactory(store, properties, executor);
        ScheduledJobLeaseStore.Lease tenantLease = new ScheduledJobLeaseStore.Lease("tenant:7:job", "a", 2L);
        ScheduledJobLeaseStore.Lease globalLease = new ScheduledJobLeaseStore.Lease("global:job", "b", 3L);
        when(store.tryAcquireCurrentTenant("job")).thenReturn(tenantLease);
        when(store.tryAcquireGlobal("job")).thenReturn(globalLease);
        when(store.tryAcquireGlobal("owned")).thenReturn(null);

        ScheduledJobLeaseGuard tenantGuard = factory.tryAcquireCurrentTenant("job");
        ScheduledJobLeaseGuard globalGuard = factory.tryAcquireGlobal("job");

        assertThat(tenantGuard).isNotNull();
        assertThat(tenantGuard.fencingToken()).isEqualTo(2L);
        assertThat(globalGuard).isNotNull();
        assertThat(globalGuard.scopeKey()).isEqualTo("global:job");
        assertThat(factory.tryAcquireGlobal("owned")).isNull();
        tenantGuard.close();
        globalGuard.close();
        verify(store).release(tenantLease);
        verify(store).release(globalLease);
    }

    @Test
    void heartbeatExecutorUsesBoundedDaemonThreadsAndSupportsImmediateShutdown() throws Exception {
        ScheduledJobLeaseProperties properties = new ScheduledJobLeaseProperties();
        properties.setHeartbeatThreads(1);
        ScheduledExecutorService executor = new ScheduledJobLeaseExecutorConfig()
            .scheduledJobLeaseHeartbeatExecutor(properties);

        try {
            String thread = executor.submit(
                () -> Thread.currentThread().getName() + ":" + Thread.currentThread().isDaemon()
            ).get(2, TimeUnit.SECONDS);

            assertThat(thread).startsWith("repoguard-scheduled-lease-heartbeat-").endsWith(":true");
            assertThat(((ScheduledThreadPoolExecutor) executor).getRemoveOnCancelPolicy()).isTrue();
        } finally {
            assertThat(executor.shutdownNow()).isEmpty();
        }
    }
}
