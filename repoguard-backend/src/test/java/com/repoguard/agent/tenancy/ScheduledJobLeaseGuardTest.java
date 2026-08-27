package com.repoguard.agent.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.when;

import com.repoguard.agent.concurrency.AsyncExecutorProperties;
import com.repoguard.agent.concurrency.BoundedExecutorFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ScheduledJobLeaseGuardTest {

    private final ScheduledJobLeaseStore store = mock(ScheduledJobLeaseStore.class);
    private final ScheduledExecutorService heartbeatExecutor = mock(ScheduledExecutorService.class);
    private final ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
    private final ScheduledJobLeaseStore.Lease lease = new ScheduledJobLeaseStore.Lease(
        "tenant:7:recovery",
        "owner",
        23L
    );

    ScheduledJobLeaseGuardTest() {
        doReturn(heartbeat).when(heartbeatExecutor)
            .scheduleWithFixedDelay(any(), anyLong(), anyLong(), eq(TimeUnit.SECONDS));
        when(store.isHeld(lease)).thenReturn(true);
        when(store.renew(lease)).thenReturn(true);
    }

    @AfterEach
    void contextsAreCleared() {
        assertThat(TenantContext.currentTenantId()).isNull();
        assertThat(ScheduledJobLeaseContext.currentFencingToken()).isNull();
    }

    @Test
    void heartbeatRenewsWithCapturedTenantAndCurrentToken() {
        ScheduledJobLeaseGuard guard;
        try (TenantContext.Scope _ = TenantContext.withTenant(7L)) {
            guard = new ScheduledJobLeaseGuard(lease, store, heartbeatExecutor, 5L);
        }
        ArgumentCaptor<Runnable> heartbeatTask = ArgumentCaptor.forClass(Runnable.class);
        verify(heartbeatExecutor).scheduleWithFixedDelay(
            heartbeatTask.capture(),
            eq(5L),
            eq(5L),
            eq(TimeUnit.SECONDS)
        );

        heartbeatTask.getValue().run();

        verify(store).renew(lease);
        guard.close();
        verify(store).release(lease);
    }

    @Test
    void capturedAsyncWorkKeepsLeaseUntilTheLastReferenceCompletes() {
        ScheduledJobLeaseGuard guard = new ScheduledJobLeaseGuard(lease, store, heartbeatExecutor, 5L);
        ScheduledJobLeaseContext.CapturedTask captured;
        try (ScheduledJobLeaseContext.Scope _ = ScheduledJobLeaseContext.withGuard(guard)) {
            captured = ScheduledJobLeaseContext.capture(
                () -> assertThat(ScheduledJobLeaseContext.currentFencingToken()).isEqualTo(23L)
            );
        }

        guard.close();
        verify(store, never()).release(lease);
        captured.run();

        verify(store).release(lease);
    }

    @Test
    void discardedAsyncWorkDoesNotLeakTheLeaseReference() {
        ScheduledJobLeaseGuard guard = new ScheduledJobLeaseGuard(lease, store, heartbeatExecutor, 5L);
        ScheduledJobLeaseContext.CapturedTask captured;
        try (ScheduledJobLeaseContext.Scope _ = ScheduledJobLeaseContext.withGuard(guard)) {
            captured = ScheduledJobLeaseContext.capture(() -> { });
        }

        guard.close();
        captured.discard();

        verify(store).release(lease);
    }

    @Test
    void boundedExecutorPropagatesFenceAndDefersReleaseUntilAsyncWorkCompletes() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AsyncExecutorProperties properties = new AsyncExecutorProperties();
        properties.setShutdownWaitSeconds(1);
        ThreadPoolExecutor executor = new BoundedExecutorFactory(registry, properties)
            .create("lease-context-test", 1, 2);
        ScheduledJobLeaseGuard guard = new ScheduledJobLeaseGuard(lease, store, heartbeatExecutor, 5L);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);

        try {
            java.util.concurrent.Future<Long> future;
            try (ScheduledJobLeaseContext.Scope _ = ScheduledJobLeaseContext.withGuard(guard)) {
                future = executor.submit(() -> {
                    started.countDown();
                    proceed.await();
                    return ScheduledJobLeaseContext.currentFencingToken();
                });
            }
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            guard.close();
            verify(store, never()).release(lease);

            proceed.countDown();
            assertThat(future.get(2, TimeUnit.SECONDS)).isEqualTo(23L);
            verify(store, timeout(1000)).release(lease);
            assertThat(executor.submit(ScheduledJobLeaseContext::currentFencingToken).get(2, TimeUnit.SECONDS))
                .isNull();
        } finally {
            proceed.countDown();
            executor.shutdownNow();
            registry.close();
        }
    }

    @Test
    void staleOwnerFailsClosedBeforeStartingAnotherBatch() {
        when(store.isHeld(lease)).thenReturn(false);
        ScheduledJobLeaseGuard guard = new ScheduledJobLeaseGuard(lease, store, heartbeatExecutor, 5L);

        assertThatThrownBy(guard::assertHeld)
            .isInstanceOf(ScheduledJobLeaseLostException.class)
            .hasMessageContaining("fencingToken=23");

        verify(heartbeat).cancel(false);
        guard.close();
        verify(store).release(lease);
    }
}
