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
