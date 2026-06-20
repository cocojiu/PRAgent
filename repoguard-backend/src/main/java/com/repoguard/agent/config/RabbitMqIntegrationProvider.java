package com.repoguard.agent.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import org.springframework.stereotype.Service;

@Service
public class RabbitMqIntegrationProvider {

    private static final String RABBITMQ_PROVIDER = "RABBITMQ";

    private final IntegrationConfigMapper integrationConfigMapper;

    public RabbitMqIntegrationProvider(IntegrationConfigMapper integrationConfigMapper) {
        this.integrationConfigMapper = integrationConfigMapper;
    }

    public RabbitMqIntegrationSettings getSettings() {
        IntegrationConfig config = integrationConfigMapper.selectOne(
            new LambdaQueryWrapper<IntegrationConfig>().eq(IntegrationConfig::getProvider, RABBITMQ_PROVIDER)
        );
        if (config == null) {
            return RabbitMqIntegrationSettings.empty();
        }
        return new RabbitMqIntegrationSettings(
            config.getProvider(),
            config.getStatus(),
            config.getBaseUrl(),
            config.getDefaultOwner(),
            config.getDefaultRepo(),
            config.getLastCheckedAt(),
            config.getLastError(),
            config.getUpdatedAt()
        );
    }
}
