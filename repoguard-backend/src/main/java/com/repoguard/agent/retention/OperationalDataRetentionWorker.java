package com.repoguard.agent.retention;

import com.repoguard.agent.config.OperationalDataRetentionProperties;
import com.repoguard.agent.config.SchedulerRuntimeEnabled;
import com.repoguard.agent.mapper.OperationalDataRetentionMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@SchedulerRuntimeEnabled
public class OperationalDataRetentionWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(OperationalDataRetentionWorker.class);
    private final OperationalDataRetentionMapper mapper;
    private final OperationalDataRetentionProperties properties;
    private final MeterRegistry meterRegistry;
    private final OperationalDataRetentionBatchExecutor batchExecutor;

    @Autowired
    public OperationalDataRetentionWorker(
        OperationalDataRetentionMapper mapper,
        OperationalDataRetentionProperties properties,
        MeterRegistry meterRegistry,
        OperationalDataRetentionBatchExecutor batchExecutor
    ) {
        this.mapper = mapper;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.batchExecutor = batchExecutor;
    }

    public OperationalDataRetentionWorker(
        OperationalDataRetentionMapper mapper,
        OperationalDataRetentionProperties properties,
        MeterRegistry meterRegistry
    ) {
        this(mapper, properties, meterRegistry, new OperationalDataRetentionBatchExecutor(mapper));
    }

    @Scheduled(cron = "${repoguard.operational-data-retention.cron:0 30 3 * * *}")
    public void cleanup() {
        if (!properties.isEnabled()) {
            return;
        }
        int limit = properties.normalizedBatchSize();
        clean("user_refresh_token", properties.getRefreshTokenDays(), cutoff -> mapper.deleteRefreshTokens(cutoff, limit));
        clean("user_login_audit", properties.getLoginAuditDays(), cutoff -> mapper.deleteLoginAudits(cutoff, limit));
        clean("user_operation_audit", properties.getOperationAuditDays(), cutoff -> mapper.deleteUserOperationAudits(cutoff, limit));
        clean("admin_operation_audit", properties.getOperationAuditDays(), cutoff -> mapper.deleteAdminOperationAudits(cutoff, limit));
        clean("system_setting_log", properties.getSystemSettingLogDays(), cutoff -> mapper.deleteSystemSettingLogs(cutoff, limit));
        clean("notification_delivery_log", properties.getNotificationLogDays(), cutoff -> mapper.deleteNotificationDeliveries(cutoff, limit));
        clean("notification_event", properties.getNotificationLogDays(), cutoff -> mapper.deleteNotificationEvents(cutoff, limit));
        clean("operational_data_cleanup_audit", properties.getOperationAuditDays(), cutoff -> mapper.deleteCleanupAudits(cutoff, limit));
    }

    private void clean(String table, int retentionDays, Function<LocalDateTime, Integer> delete) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(Math.max(1, retentionDays));
        int limit = properties.normalizedBatchSize();
        int maxBatches = properties.normalizedMaxBatchesPerRun();
        for (int batch = 0; batch < maxBatches; batch++) {
            try {
                int deleted = batchExecutor.deleteAndAudit(table, cutoff, () -> delete.apply(cutoff));
                meterRegistry.counter("repoguard.operational.retention.deleted", "table", table).increment(deleted);
                if (deleted < limit) {
                    return;
                }
            } catch (RuntimeException ex) {
                try {
                    batchExecutor.recordFailure(table, cutoff, ex);
                } catch (RuntimeException auditFailure) {
                    ex.addSuppressed(auditFailure);
                }
                meterRegistry.counter("repoguard.operational.retention.failed", "table", table).increment();
                LOGGER.error("Operational retention cleanup failed table={}", table, ex);
                return;
            }
        }
        meterRegistry.counter("repoguard.operational.retention.backlog", "table", table).increment();
        LOGGER.warn("Operational retention cleanup reached the batch limit table={} batches={}", table, maxBatches);
    }
}
