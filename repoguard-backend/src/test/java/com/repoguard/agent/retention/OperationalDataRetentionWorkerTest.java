package com.repoguard.agent.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
}
