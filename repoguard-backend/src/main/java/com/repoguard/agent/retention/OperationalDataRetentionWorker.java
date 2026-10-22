package com.repoguard.agent.retention;

import com.repoguard.agent.config.OperationalDataRetentionProperties;
import com.repoguard.agent.config.SchedulerRuntimeEnabled;
import com.repoguard.agent.mapper.OperationalDataRetentionMapper;
import com.repoguard.agent.tenancy.ScheduledJobLeaseContext;
import com.repoguard.agent.tenancy.TenantContext;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@SchedulerRuntimeEnabled
public class OperationalDataRetentionWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(OperationalDataRetentionWorker.class);
    private final OperationalDataRetentionMapper mapper;
    private final OperationalDataRetentionProperties properties;
    private final MeterRegistry meterRegistry;
    private final OperationalDataRetentionBatchExecutor batchExecutor;
    private final LongSupplier nanoClock;
    private final Cache<Long, Integer> nextTable = Caffeine.newBuilder().maximumSize(2048)
        .expireAfterAccess(java.time.Duration.ofDays(1)).build();

    @Autowired
    public OperationalDataRetentionWorker(
        OperationalDataRetentionMapper mapper,
        OperationalDataRetentionProperties properties,
        MeterRegistry meterRegistry,
        OperationalDataRetentionBatchExecutor batchExecutor
    ) {
        this(mapper, properties, meterRegistry, batchExecutor, System::nanoTime);
    }

    OperationalDataRetentionWorker(OperationalDataRetentionMapper mapper, OperationalDataRetentionProperties properties,
        MeterRegistry meterRegistry, OperationalDataRetentionBatchExecutor batchExecutor, LongSupplier nanoClock) {
        this.mapper = mapper;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.batchExecutor = batchExecutor;
        this.nanoClock = nanoClock;
    }

    public OperationalDataRetentionWorker(
        OperationalDataRetentionMapper mapper,
        OperationalDataRetentionProperties properties,
        MeterRegistry meterRegistry
    ) {
        this(mapper, properties, meterRegistry, new OperationalDataRetentionBatchExecutor(mapper));
    }

    public void cleanup() {
        if (properties.isEnabled()) TenantContext.requireTenantId();
        cleanupGlobalData();
        cleanupTenantData();
    }

    public void cleanupGlobalData() {
        if (!properties.isEnabled()) {
            return;
        }
        run(0L, List.of(
            new Cleanup("user_refresh_token", properties.getRefreshTokenDays(), mapper::deleteRefreshTokens),
            new Cleanup("user_login_audit", properties.getLoginAuditDays(), mapper::deleteLoginAudits),
            new Cleanup("user_operation_audit", properties.getOperationAuditDays(), mapper::deleteUserOperationAudits),
            new Cleanup("admin_operation_audit", properties.getOperationAuditDays(), mapper::deleteAdminOperationAudits),
            new Cleanup("operational_data_cleanup_audit", properties.getOperationAuditDays(), mapper::deleteCleanupAudits)));
    }

    public void cleanupTenantData() {
        if (!properties.isEnabled()) {
            return;
        }
        long tenantId = TenantContext.requireTenantId();
        List<Cleanup> work = new ArrayList<>(List.of(
            new Cleanup("system_setting_log", properties.getSystemSettingLogDays(), mapper::deleteSystemSettingLogs),
            new Cleanup("notification_delivery_log", properties.getNotificationLogDays(), mapper::deleteNotificationDeliveries),
            new Cleanup("notification_event", properties.getNotificationLogDays(), mapper::deleteNotificationEvents),
            new Cleanup("tenant_quota_usage", properties.getTenantQuotaUsageDays(),
                (cutoff, limit) -> mapper.deleteTenantQuotaUsage(tenantId, cutoff, limit))));
        if (properties.isFeedbackPayloadPurgeEnabled()) {
            work.add(new Cleanup("github_feedback_event_payload", properties.normalizedFeedbackPayloadDays(),
                (cutoff, limit) -> mapper.purgeFeedbackPayload(tenantId, cutoff, limit)));
        }
        if (properties.isEvaluationRunPayloadPurgeEnabled()) {
            work.add(new Cleanup("llm_evaluation_run_payload", properties.normalizedEvaluationRunPayloadDays(),
                (cutoff, limit) -> mapper.purgeEvaluationRunPayload(tenantId, cutoff, limit)));
        }
        run(tenantId, work);
    }

    private void run(long scope, List<Cleanup> work) {
        long startedAt = nanoClock.getAsLong();
        LocalDateTime now = LocalDateTime.now();
        int remaining = properties.normalizedMaxRowsPerRun();
        int[] batches = new int[work.size()];
        int first = Math.floorMod(nextTable.get(scope,
            ignored -> Math.floorMod(now.toLocalDate().toEpochDay(), work.size())), work.size());
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        for (int index = 0; index < work.size(); index++) pending.add((first + index) % work.size());
        while (!pending.isEmpty() && remaining > 0
            && nanoClock.getAsLong() - startedAt < TimeUnit.MILLISECONDS.toNanos(properties.normalizedMaxRunMillis())) {
            int index = pending.removeFirst();
            Cleanup item = work.get(index);
            nextTable.put(scope, (index + 1) % work.size());
            int limit = Math.min(properties.normalizedBatchSize(), remaining);
            LocalDateTime cutoff = now.minusDays(Math.max(1, item.days));
            ScheduledJobLeaseContext.assertHeld();
            long batchStartedAt = nanoClock.getAsLong();
            try {
                int deleted = batchExecutor.deleteAndAudit(item.table, cutoff, () -> item.delete.apply(cutoff, limit));
                remaining -= deleted;
                meterRegistry.counter("repoguard.operational.retention.deleted", "table", item.table).increment(deleted);
                if (deleted >= limit) {
                    if (++batches[index] < properties.normalizedMaxBatchesPerRun()) pending.addLast(index);
                    else meterRegistry.counter("repoguard.operational.retention.backlog", "table", item.table).increment();
                }
            } catch (RuntimeException ex) {
                try {
                    batchExecutor.recordFailure(item.table, cutoff, ex);
                } catch (RuntimeException auditFailure) {
                    ex.addSuppressed(auditFailure);
                }
                meterRegistry.counter("repoguard.operational.retention.failed", "table", item.table).increment();
                LOGGER.error("Operational retention cleanup failed table={}", item.table, ex);
            } finally {
                meterRegistry.timer("repoguard.operational.retention.batch.duration", "table", item.table)
                    .record(nanoClock.getAsLong() - batchStartedAt, TimeUnit.NANOSECONDS);
            }
        }
        if (!pending.isEmpty()) meterRegistry.counter("repoguard.operational.retention.run.stopped", "reason",
            remaining <= 0 ? "rows" : "deadline").increment();
    }

    private record Cleanup(String table, int days, BiFunction<LocalDateTime, Integer, Integer> delete) { }
}
