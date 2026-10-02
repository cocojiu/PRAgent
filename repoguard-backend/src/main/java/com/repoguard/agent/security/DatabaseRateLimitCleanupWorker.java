package com.repoguard.agent.security;

import com.repoguard.agent.config.SchedulerRuntimeEnabled;
import com.repoguard.agent.tenancy.ScheduledJobLeaseContext;
import com.repoguard.agent.tenancy.TenantScheduledTaskRunner;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@SchedulerRuntimeEnabled
@ConditionalOnProperty(name = "app.security.rate-limit-store", havingValue = "database")
public class DatabaseRateLimitCleanupWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseRateLimitCleanupWorker.class);
    private static final String METRIC = "repoguard.security.shared_rate_limit.cleanup.";
    private final DatabaseRateLimitCleanupBatchExecutor executor;
    private final DatabaseRateLimitCleanupProperties properties;
    private final TenantScheduledTaskRunner runner;
    private final MeterRegistry metrics;

    public DatabaseRateLimitCleanupWorker(DatabaseRateLimitCleanupBatchExecutor executor,
        DatabaseRateLimitCleanupProperties properties, TenantScheduledTaskRunner runner, MeterRegistry metrics) {
        this.executor = executor;
        this.properties = properties;
        this.runner = runner;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${app.security.rate-limit-cleanup.interval-ms:60000}")
    public void cleanup() {
        if (properties.isEnabled()) runner.runGlobal("shared_rate_limit_cleanup", this::cleanupUnderLease);
    }

    private void cleanupUnderLease() {
        long cutoff = Instant.now().getEpochSecond() / 60 - 2;
        long deadline = System.nanoTime() + properties.normalizedMaxRunMs() * 1_000_000L;
        int limit = properties.normalizedBatchSize();
        var sample = Timer.start(metrics);
        String outcome = "capped";
        try {
            for (int batch = 0; batch < properties.normalizedMaxBatchesPerRun(); batch++) {
                if (System.nanoTime() >= deadline) break;
                ScheduledJobLeaseContext.assertHeld();
                int deleted = executor.deleteExpired(cutoff, limit);
                metrics.counter(METRIC + "deleted_rows").increment(deleted);
                if (deleted < limit) { outcome = "drained"; return; }
            }
            metrics.counter(METRIC + "backlog").increment();
        } catch (RuntimeException ex) {
            outcome = "failed";
            metrics.counter(METRIC + "failed").increment();
            LOGGER.warn("Shared rate-limit background cleanup failed; active counters remain enforced", ex);
        } finally {
            sample.stop(Timer.builder(METRIC + "duration").tag("outcome", outcome).register(metrics));
        }
    }
}
