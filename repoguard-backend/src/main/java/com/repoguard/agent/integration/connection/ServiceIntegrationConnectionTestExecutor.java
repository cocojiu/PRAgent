package com.repoguard.agent.integration.connection;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.dto.ServiceIntegrationConfigRequest;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class ServiceIntegrationConnectionTestExecutor {

    private final IntegrationConfigMapper integrationConfigMapper;
    private final ConnectionTestConfigFactory configFactory;
    private final IntegrationConnectionCheckMarker connectionCheckMarker;

    ServiceIntegrationConnectionTestExecutor(
        IntegrationConfigMapper integrationConfigMapper,
        ConnectionTestConfigFactory configFactory,
        IntegrationConnectionCheckMarker connectionCheckMarker
    ) {
        this.integrationConfigMapper = Objects.requireNonNull(integrationConfigMapper, "integrationConfigMapper");
        this.configFactory = Objects.requireNonNull(configFactory, "configFactory");
        this.connectionCheckMarker = Objects.requireNonNull(connectionCheckMarker, "connectionCheckMarker");
    }

    ConnectionTestResultDto test(
        String provider,
        ServiceIntegrationConfigRequest configRequest,
        ServiceIntegrationConnectionTestRunner runner
    ) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(runner, "runner");

        IntegrationConfig savedConfig = findServiceIntegration(provider);
        boolean transientConfig = configRequest != null;
        IntegrationConfig config = transientConfig
            ? configFactory.serviceIntegrationForTest(provider, configRequest, savedConfig)
            : savedConfig;
        return runner.run(savedConfig, config, transientConfig, connectionCheckMarker::markChecked);
    }

    private IntegrationConfig findServiceIntegration(String provider) {
        return integrationConfigMapper.selectOne(
            new LambdaQueryWrapper<IntegrationConfig>().eq(IntegrationConfig::getProvider, provider)
        );
    }
}
