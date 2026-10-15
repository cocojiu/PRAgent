package com.repoguard.agent.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.tenancy.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantContextExecutorPropagationTest {

    @AfterEach
    void tenantContextIsAlwaysCleared() {
        assertThat(TenantContext.currentTenantId()).isNull();
    }

    @Test
    void wrappedTaskInstallsCapturedTenantAndRestoresWorkerContext() {
        Runnable wrapped;
        try (TenantContext.Scope _ = TenantContext.withTenant(7L)) {
            wrapped = TenantContext.wrap(() -> assertThat(TenantContext.currentTenantId()).isEqualTo(7L));
        }

        try (TenantContext.Scope _ = TenantContext.withTenant(9L)) {
            wrapped.run();
            assertThat(TenantContext.currentTenantId()).isEqualTo(9L);
        }
    }

    @Test
    void emptyCaptureClearsWorkerTenantAndRestoresItAfterFailure() {
        Runnable wrapped = TenantContext.wrap(() -> {
            assertThat(TenantContext.currentTenantId()).isNull();
            throw new IllegalStateException("expected");
        });
        try (var _ = TenantContext.withTenant(9L)) {
            assertThatThrownBy(wrapped::run).isInstanceOf(IllegalStateException.class);
            assertThat(TenantContext.currentTenantId()).isEqualTo(9L);
        }
    }

    @Test
    void cancellingQueuedTenantTaskCannotLeakIntoNextSubmission() throws Exception {
        var registry = new SimpleMeterRegistry();
        var executor = new BoundedExecutorFactory(registry, new AsyncExecutorProperties()).create("cancel-tenant", 1, 4);
        var started = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try {
            executor.submit(() -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            java.util.concurrent.Future<?> cancelled;
            try (var _ = TenantContext.withTenant(21L)) {
                cancelled = executor.submit(() -> { throw new AssertionError("cancelled task ran"); });
            }
            assertThat(cancelled.cancel(false)).isTrue();
            release.countDown();
            try (var _ = TenantContext.withTenant(22L)) {
                assertThat(executor.submit(TenantContext::currentTenantId).get(2, TimeUnit.SECONDS)).isEqualTo(22L);
            }
            assertThat(executor.submit(TenantContext::currentTenantId).get(2, TimeUnit.SECONDS)).isNull();
        } finally { release.countDown(); executor.shutdownNow(); registry.close(); }
    }

    @Test
    void boundedExecutorCapturesTenantAtSubmissionAndClearsAfterFailure() throws Exception {
        AsyncExecutorProperties properties = new AsyncExecutorProperties();
        properties.setShutdownWaitSeconds(1);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreadPoolExecutor executor = new BoundedExecutorFactory(registry, properties)
            .create("tenant-context-test", 1, 4);

        try {
            java.util.concurrent.Future<Long> tenantFuture;
            try (TenantContext.Scope _ = TenantContext.withTenant(21L)) {
                tenantFuture = executor.submit(TenantContext::currentTenantId);
            }
            assertThat(tenantFuture.get(2, TimeUnit.SECONDS)).isEqualTo(21L);

            java.util.concurrent.Future<?> failed;
            try (TenantContext.Scope _ = TenantContext.withTenant(22L)) {
                failed = executor.submit(() -> {
                    assertThat(TenantContext.currentTenantId()).isEqualTo(22L);
                    throw new IllegalStateException("expected");
                });
            }
            assertThatThrownBy(() -> failed.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
            assertThat(executor.submit(TenantContext::currentTenantId).get(2, TimeUnit.SECONDS)).isNull();
        } finally {
            executor.shutdownNow();
            registry.close();
        }
    }
}
