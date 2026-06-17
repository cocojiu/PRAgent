package com.repoguard.agent.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.security.SecretCryptoService;
import org.springframework.stereotype.Service;

@Service
public class GithubIntegrationProvider {

    private static final String GITHUB_PROVIDER = "GITHUB";

    private final IntegrationConfigMapper integrationConfigMapper;
    private final SecretCryptoService secretCryptoService;

    public GithubIntegrationProvider(
        IntegrationConfigMapper integrationConfigMapper,
        SecretCryptoService secretCryptoService
    ) {
        this.integrationConfigMapper = integrationConfigMapper;
        this.secretCryptoService = secretCryptoService;
    }

    public GithubIntegrationSettings getSettings() {
        IntegrationConfig config = integrationConfigMapper.selectOne(
            new LambdaQueryWrapper<IntegrationConfig>().eq(IntegrationConfig::getProvider, GITHUB_PROVIDER)
        );
        if (config == null) {
            return GithubIntegrationSettings.empty();
        }
        return new GithubIntegrationSettings(
            config.getProvider(),
            config.getStatus(),
            config.getBaseUrl(),
            secretCryptoService.decrypt(config.getTokenValue())
        );
    }
}
