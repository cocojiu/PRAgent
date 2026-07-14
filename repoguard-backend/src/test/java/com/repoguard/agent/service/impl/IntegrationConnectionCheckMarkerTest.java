package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class IntegrationConnectionCheckMarkerTest {

    private final IntegrationConfigMapper integrationConfigMapper = Mockito.mock(IntegrationConfigMapper.class);
    private final IntegrationConnectionCheckMarker marker = new IntegrationConnectionCheckMarker(integrationConfigMapper);

    @Test
    void marksConnectionSuccessAndClearsStaleError() {
        IntegrationConfig config = integrationConfig(7L);
        config.setLastError("stale error");

        marker.markChecked(config, null);

        assertThat(config.getStatus()).isEqualTo("CONFIGURED");
        assertThat(config.getLastError()).isNull();
        assertThat(config.getLastCheckedAt()).isNotNull();
        assertThat(config.getUpdatedAt()).isNotNull();
        verify(integrationConfigMapper).update(org.mockito.ArgumentMatchers.isNull(), any(UpdateWrapper.class));
    }

    @Test
    void marksConnectionFailureWithoutClearingErrorColumn() {
        IntegrationConfig config = integrationConfig(8L);

        marker.markChecked(config, "timeout");

        assertThat(config.getStatus()).isEqualTo("FAILED");
        assertThat(config.getLastError()).isEqualTo("timeout");
        assertThat(config.getLastCheckedAt()).isNotNull();
        verify(integrationConfigMapper).update(org.mockito.ArgumentMatchers.isNull(), any(UpdateWrapper.class));
    }

    @Test
    void skipsTransientConfigWithoutIdentifier() {
        marker.markChecked(new IntegrationConfig(), null);

        verify(integrationConfigMapper, never()).updateById(any(IntegrationConfig.class));
        verify(integrationConfigMapper, never()).update(
            org.mockito.ArgumentMatchers.isNull(),
            any(UpdateWrapper.class)
        );
    }

    @Test
    void skipsSavedConfigWithoutOriginalVersion() {
        IntegrationConfig config = new IntegrationConfig();
        config.setId(9L);

        marker.markChecked(config, null);

        verify(integrationConfigMapper, never()).update(
            org.mockito.ArgumentMatchers.isNull(),
            any(UpdateWrapper.class)
        );
    }

    private IntegrationConfig integrationConfig(Long id) {
        IntegrationConfig config = new IntegrationConfig();
        config.setId(id);
        config.setProvider("GITHUB");
        config.setUpdatedAt(LocalDateTime.parse("2026-07-13T12:00:00"));
        return config;
    }
}
