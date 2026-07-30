package com.repoguard.agent.integration.connection;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.dto.GithubIntegrationConfigRequest;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class GithubIntegrationConnectionTestExecutor {

    private final IntegrationConfigMapper integrationConfigMapper;
    private final ConnectionTestConfigFactory configFactory;
    private final IntegrationConnectionCheckMarker connectionCheckMarker;

    GithubIntegrationConnectionTestExecutor(
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
        GithubIntegrationConfigRequest configRequest,
        GithubIntegrationConnectionTestRunner runner
    ) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(runner, "runner");

        IntegrationConfig savedConfig = findGithubConfig(provider);
        boolean transientConfig = configRequest != null;
        IntegrationConfig config = transientConfig
            ? configFactory.githubIntegrationForTest(provider, configRequest, savedConfig)
            : savedConfig;
        return runner.run(config, transientConfig, connectionCheckMarker::markChecked);
    }

    private IntegrationConfig findGithubConfig(String provider) {
        return integrationConfigMapper.selectOne(
            new LambdaQueryWrapper<IntegrationConfig>().eq(IntegrationConfig::getProvider, provider)
        );
    }
}
