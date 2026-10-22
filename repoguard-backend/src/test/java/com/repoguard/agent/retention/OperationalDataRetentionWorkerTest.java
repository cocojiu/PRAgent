package com.repoguard.agent.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.config.OperationalDataRetentionProperties;
import com.repoguard.agent.mapper.OperationalDataRetentionMapper;
import com.repoguard.agent.tenancy.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class OperationalDataRetentionWorkerTest {
    private TenantContext.Scope personalScope;
    @org.junit.jupiter.api.BeforeEach
    void personalEntry() { personalScope = TenantContext.withTenant(TenantContext.DEFAULT_TENANT_ID); }
    @org.junit.jupiter.api.AfterEach
    void clearPersonalEntry() { personalScope.close(); }

    @Test
    void missingTenantCannotStartTenantOrCombinedCleanup() {
        personalScope.close();
        var mapper = mock(OperationalDataRetentionMapper.class);
        var registry = new SimpleMeterRegistry();
        var worker = new OperationalDataRetentionWorker(mapper, new OperationalDataRetentionProperties(), registry);
        org.assertj.core.api.Assertions.assertThatThrownBy(worker::cleanupTenantData).isInstanceOf(IllegalStateException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(worker::cleanup).isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verifyNoInteractions(mapper);
        registry.close();
    }

    @Test
    void cleanupIncludesItsOwnAuditTable() {
        OperationalDataRetentionMapper mapper = mock(OperationalDataRetentionMapper.class);
        OperationalDataRetentionProperties properties = new OperationalDataRetentionProperties();
        properties.setBatchSize(37);

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        new OperationalDataRetentionWorker(mapper, properties, meterRegistry).cleanup();
        meterRegistry.close();

        verify(mapper).deleteRefreshTokens(any(), eq(37));
        verify(mapper).deleteLoginAudits(any(), eq(37));
        verify(mapper).deleteUserOperationAudits(any(), eq(37));
        verify(mapper).deleteAdminOperationAudits(any(), eq(37));
        verify(mapper).deleteSystemSettingLogs(any(), eq(37));
        verify(mapper).deleteNotificationDeliveries(any(), eq(37));
        verify(mapper).deleteNotificationEvents(any(), eq(37));
        verify(mapper).deleteTenantQuotaUsage(eq(1L), any(), eq(37));
        verify(mapper).deleteCleanupAudits(any(), eq(37));
        verify(mapper, never()).purgeFeedbackPayload(anyLong(), any(), anyInt());
        verify(mapper, never()).purgeEvaluationRunPayload(anyLong(), any(), anyInt());
    }

    @Test
    void tenantCleanupAuditsTenantAndDoesNotRunGlobalDeletes() {
        OperationalDataRetentionMapper mapper = mock(OperationalDataRetentionMapper.class);
        OperationalDataRetentionProperties properties = new OperationalDataRetentionProperties();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        try (TenantContext.Scope _ = TenantContext.withTenant(23L)) {
            new OperationalDataRetentionWorker(mapper, properties, meterRegistry).cleanupTenantData();
        }

        verify(mapper, times(4)).insertAudit(eq(23L), anyString(), any(), eq(0), eq("SUCCESS"), isNull());
        verify(mapper, never()).deleteRefreshTokens(any(), anyInt());
        verify(mapper, never()).deleteCleanupAudits(any(), anyInt());
        meterRegistry.close();
    }

    @Test
    void cleanupDoesNothingWhenDisabled() {
        OperationalDataRetentionMapper mapper = mock(OperationalDataRetentionMapper.class);
        OperationalDataRetentionProperties properties = new OperationalDataRetentionProperties();
        properties.setEnabled(false);

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        new OperationalDataRetentionWorker(mapper, properties, meterRegistry).cleanup();
        meterRegistry.close();

        verify(mapper, never()).insertAudit(isNull(), anyString(), any(), anyInt(), anyString(), isNull());
    }

    @Test
    void cleanupBoundsBacklogCatchUpByConfiguredBatchCount() {
        OperationalDataRetentionMapper mapper = mock(OperationalDataRetentionMapper.class);
        OperationalDataRetentionProperties properties = new OperationalDataRetentionProperties();
        properties.setBatchSize(1);
        properties.setMaxBatchesPerRun(2);
        when(mapper.deleteRefreshTokens(any(), eq(1))).thenReturn(1);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        new OperationalDataRetentionWorker(mapper, properties, meterRegistry).cleanup();

        verify(mapper, times(2)).deleteRefreshTokens(any(), eq(1));
        assertThat(
            meterRegistry.get("repoguard.operational.retention.backlog")
                .tag("table", "user_refresh_token")
                .counter()
                .count()
        ).isEqualTo(1.0);
        meterRegistry.close();
    }

    @Test
    void payloadPurgingIsOptInTenantScopedAndBounded() {
        var mapper = mock(OperationalDataRetentionMapper.class);
        var properties = new OperationalDataRetentionProperties();
        properties.setFeedbackPayloadPurgeEnabled(true);
        properties.setEvaluationRunPayloadPurgeEnabled(true);
        properties.setBatchSize(2);
        properties.setMaxBatchesPerRun(2);
        when(mapper.purgeFeedbackPayload(eq(23L), any(), eq(2))).thenReturn(2);
        var registry = new SimpleMeterRegistry();
        try (TenantContext.Scope _ = TenantContext.withTenant(23L)) {
            new OperationalDataRetentionWorker(mapper, properties, registry).cleanupTenantData();
        }
        verify(mapper, times(2)).purgeFeedbackPayload(eq(23L), any(), eq(2));
        verify(mapper).purgeEvaluationRunPayload(eq(23L), any(), eq(2));
        assertThat(registry.get("repoguard.operational.retention.backlog")
            .tag("table", "github_feedback_event_payload").counter().count()).isEqualTo(1);
        registry.close();
    }

    @Test
    void rowBudgetIsSharedAcrossTablesAndNextRunResumesFairly() {
        var mapper = mock(OperationalDataRetentionMapper.class);
        var properties = new OperationalDataRetentionProperties();
        properties.setBatchSize(5);
        properties.setMaxRowsPerRun(1);
        when(mapper.deleteSystemSettingLogs(any(), eq(1))).thenReturn(1);
        when(mapper.deleteNotificationDeliveries(any(), eq(1))).thenReturn(1);
        when(mapper.deleteNotificationEvents(any(), eq(1))).thenReturn(1);
        when(mapper.deleteTenantQuotaUsage(anyLong(), any(), eq(1))).thenReturn(1);
        var registry = new SimpleMeterRegistry();
        var worker = new OperationalDataRetentionWorker(mapper, properties, registry);
        for (int index = 0; index < 4; index++) worker.cleanupTenantData();
        verify(mapper).deleteSystemSettingLogs(any(), eq(1));
        verify(mapper).deleteNotificationDeliveries(any(), eq(1));
        verify(mapper).deleteNotificationEvents(any(), eq(1));
        verify(mapper).deleteTenantQuotaUsage(eq(1L), any(), eq(1));
        assertThat(registry.get("repoguard.operational.retention.run.stopped").tag("reason", "rows")
            .counter().count()).isEqualTo(4);
        registry.close();
    }

    @Test
    void deadlineStopsAtBatchBoundaryAndNextRunStartsWithAnotherTable() {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        java.util.List<String> deletedTables = new java.util.ArrayList<>();
        var mapper = mock(OperationalDataRetentionMapper.class, invocation -> {
            if (invocation.getMethod().getName().startsWith("delete")) {
                deletedTables.add(invocation.getMethod().getName());
                clock.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(1));
                return 2;
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        var properties = new OperationalDataRetentionProperties();
        properties.setBatchSize(2);
        properties.setMaxRunMillis(1000);
        var registry = new SimpleMeterRegistry();
        var worker = new OperationalDataRetentionWorker(mapper, properties, registry,
            new OperationalDataRetentionBatchExecutor(mapper), clock::get);
        worker.cleanupTenantData();
        worker.cleanupTenantData();
        assertThat(deletedTables).hasSize(2).doesNotHaveDuplicates();
        assertThat(registry.get("repoguard.operational.retention.run.stopped").tag("reason", "deadline")
            .counter().count()).isEqualTo(2);
        registry.close();
    }
}
