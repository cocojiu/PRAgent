package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
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
        verify(integrationConfigMapper).updateById(config);
        verify(integrationConfigMapper).update(any(UpdateWrapper.class));
    }

    @Test
    void marksConnectionFailureWithoutClearingErrorColumn() {
        IntegrationConfig config = integrationConfig(8L);

        marker.markChecked(config, "timeout");

        assertThat(config.getStatus()).isEqualTo("FAILED");
        assertThat(config.getLastError()).isEqualTo("timeout");
        assertThat(config.getLastCheckedAt()).isNotNull();
        verify(integrationConfigMapper).updateById(config);
        verify(integrationConfigMapper, never()).update(any(UpdateWrapper.class));
    }

    @Test
    void skipsTransientConfigWithoutIdentifier() {
        marker.markChecked(new IntegrationConfig(), null);

        verify(integrationConfigMapper, never()).updateById(any(IntegrationConfig.class));
        verify(integrationConfigMapper, never()).update(any(UpdateWrapper.class));
    }

    private IntegrationConfig integrationConfig(Long id) {
        IntegrationConfig config = new IntegrationConfig();
        config.setId(id);
        config.setProvider("GITHUB");
        return config;
    }
}
