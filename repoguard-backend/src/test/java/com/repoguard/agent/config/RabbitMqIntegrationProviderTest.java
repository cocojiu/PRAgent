package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class RabbitMqIntegrationProviderTest {

    private final IntegrationConfigMapper integrationConfigMapper = org.mockito.Mockito.mock(IntegrationConfigMapper.class);
    private final RabbitMqIntegrationProvider provider = new RabbitMqIntegrationProvider(integrationConfigMapper);

    @Test
    void getSettingsReturnsRabbitMqConfiguration() {
        IntegrationConfig config = new IntegrationConfig();
        config.setProvider("RABBITMQ");
        config.setStatus("CONNECTED");
        config.setBaseUrl("amqp://localhost:5672");
        config.setDefaultOwner("repoguard");
        config.setDefaultRepo("/");
        config.setLastCheckedAt(LocalDateTime.of(2026, 6, 10, 21, 2, 12));
        config.setLastError("last failure");
        config.setUpdatedAt(LocalDateTime.of(2026, 6, 10, 20, 58));
        when(integrationConfigMapper.selectOne(any())).thenReturn(config);

        RabbitMqIntegrationSettings settings = provider.getSettings();

        assertThat(settings.provider()).isEqualTo("RABBITMQ");
        assertThat(settings.status()).isEqualTo("CONNECTED");
        assertThat(settings.baseUrl()).isEqualTo("amqp://localhost:5672");
        assertThat(settings.username()).isEqualTo("repoguard");
        assertThat(settings.virtualHost()).isEqualTo("/");
        assertThat(settings.lastError()).isEqualTo("last failure");
        assertThat(settings.updatedAt()).isEqualTo(LocalDateTime.of(2026, 6, 10, 20, 58));
    }

    @Test
    void getSettingsReturnsEmptySettingsWhenConfigurationIsMissing() {
        when(integrationConfigMapper.selectOne(any())).thenReturn(null);

        RabbitMqIntegrationSettings settings = provider.getSettings();

        assertThat(settings.provider()).isEqualTo("RABBITMQ");
        assertThat(settings.status()).isEqualTo("NOT_CONFIGURED");
        assertThat(settings.baseUrl()).isNull();
    }
}
