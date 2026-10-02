package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.repoguard.agent.tenancy.TenantScheduledTaskRunner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class DatabaseRateLimitCleanupWorkerTest {
    @Test
    void globallyLeasedCleanupHasBoundedBatchesAndBacklogMetric() {
        var executor = mock(DatabaseRateLimitCleanupBatchExecutor.class);
        var runner = mock(TenantScheduledTaskRunner.class);
        var properties = new DatabaseRateLimitCleanupProperties();
        properties.setBatchSize(2);
        properties.setMaxBatchesPerRun(2);
        var metrics = new SimpleMeterRegistry();
        when(runner.runGlobal(eq("shared_rate_limit_cleanup"), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run(); return true;
        });
        when(executor.deleteExpired(anyLong(), eq(2))).thenReturn(2);
        new DatabaseRateLimitCleanupWorker(executor, properties, runner, metrics).cleanup();
        verify(executor, times(2)).deleteExpired(anyLong(), eq(2));
        assertThat(metrics.get("repoguard.security.shared_rate_limit.cleanup.deleted_rows").counter().count()).isEqualTo(4);
        assertThat(metrics.get("repoguard.security.shared_rate_limit.cleanup.backlog").counter().count()).isEqualTo(1);
        properties.setEnabled(false);
        new DatabaseRateLimitCleanupWorker(executor, properties, runner, metrics).cleanup();
        verify(runner, times(1)).runGlobal(any(), any());
        metrics.close();
    }

    @Test
    void cleanupFailureStopsThisPassAndCanBeRetriedWithoutAffectingRequestCounters() {
        var executor = mock(DatabaseRateLimitCleanupBatchExecutor.class);
        var runner = mock(TenantScheduledTaskRunner.class);
        var metrics = new SimpleMeterRegistry();
        when(runner.runGlobal(any(), any(Runnable.class))).thenAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run(); return true;
        });
        when(executor.deleteExpired(anyLong(), anyInt()))
            .thenThrow(new org.springframework.dao.CannotAcquireLockException("isolated lock failure")).thenReturn(0);
        var worker = new DatabaseRateLimitCleanupWorker(executor, new DatabaseRateLimitCleanupProperties(), runner, metrics);
        worker.cleanup();
        worker.cleanup();
        verify(executor, times(2)).deleteExpired(anyLong(), anyInt());
        assertThat(metrics.get("repoguard.security.shared_rate_limit.cleanup.failed").counter().count()).isEqualTo(1);
        assertThat(metrics.get("repoguard.security.shared_rate_limit.cleanup.duration").tag("outcome", "drained")
            .timer().count()).isEqualTo(1);
        assertThat(metrics.find("repoguard.security.shared_rate_limit.fail_closed").counter()).isNull();
        metrics.close();
    }
}
