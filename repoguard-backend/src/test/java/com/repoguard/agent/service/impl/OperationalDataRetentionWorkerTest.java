package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.repoguard.agent.config.OperationalDataRetentionProperties;
import com.repoguard.agent.mapper.OperationalDataRetentionMapper;
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

        verify(mapper).deleteRefreshTokens(any(), org.mockito.ArgumentMatchers.eq(37));
        verify(mapper).deleteLoginAudits(any(), org.mockito.ArgumentMatchers.eq(37));
        verify(mapper).deleteUserOperationAudits(any(), org.mockito.ArgumentMatchers.eq(37));
        verify(mapper).deleteAdminOperationAudits(any(), org.mockito.ArgumentMatchers.eq(37));
        verify(mapper).deleteSystemSettingLogs(any(), org.mockito.ArgumentMatchers.eq(37));
        verify(mapper).deleteNotificationDeliveries(any(), org.mockito.ArgumentMatchers.eq(37));
        verify(mapper).deleteNotificationEvents(any(), org.mockito.ArgumentMatchers.eq(37));
        verify(mapper).deleteCleanupAudits(any(), org.mockito.ArgumentMatchers.eq(37));
    }

    @Test
    void cleanupDoesNothingWhenDisabled() {
        OperationalDataRetentionMapper mapper = mock(OperationalDataRetentionMapper.class);
        OperationalDataRetentionProperties properties = new OperationalDataRetentionProperties();
        properties.setEnabled(false);

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        new OperationalDataRetentionWorker(mapper, properties, meterRegistry).cleanup();
        meterRegistry.close();

        verify(mapper, never()).insertAudit(anyString(), any(), anyInt(), anyString(), isNull());
    }

    @Test
    void cleanupBoundsBacklogCatchUpByConfiguredBatchCount() {
        OperationalDataRetentionMapper mapper = mock(OperationalDataRetentionMapper.class);
        OperationalDataRetentionProperties properties = new OperationalDataRetentionProperties();
        properties.setBatchSize(1);
        properties.setMaxBatchesPerRun(2);
        when(mapper.deleteRefreshTokens(any(), org.mockito.ArgumentMatchers.eq(1))).thenReturn(1);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        new OperationalDataRetentionWorker(mapper, properties, meterRegistry).cleanup();

        verify(mapper, times(2)).deleteRefreshTokens(any(), org.mockito.ArgumentMatchers.eq(1));
        assertThat(
            meterRegistry.get("repoguard.operational.retention.backlog")
                .tag("table", "user_refresh_token")
                .counter()
                .count()
        ).isEqualTo(1.0);
        meterRegistry.close();
    }
}
