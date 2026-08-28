package com.repoguard.agent.github;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.security.SecretCryptoService;
import com.repoguard.agent.tenancy.TenantRepositoryBinding;
import com.repoguard.agent.tenancy.TenantRepositoryResolver;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class GithubIntegrationProvider {

    private static final String GITHUB_PROVIDER = "GITHUB";

    private final IntegrationConfigMapper integrationConfigMapper;
    private final SecretCryptoService secretCryptoService;
    private final GithubAppTokenService githubAppTokenService;
    private final TenantRepositoryResolver tenantRepositoryResolver;

    @Autowired
    public GithubIntegrationProvider(
        IntegrationConfigMapper integrationConfigMapper,
        SecretCryptoService secretCryptoService,
        GithubAppTokenService githubAppTokenService,
        TenantRepositoryResolver tenantRepositoryResolver
    ) {
        this.integrationConfigMapper = integrationConfigMapper;
        this.secretCryptoService = secretCryptoService;
        this.githubAppTokenService = githubAppTokenService;
        this.tenantRepositoryResolver = tenantRepositoryResolver;
    }

    public GithubIntegrationProvider(
        IntegrationConfigMapper integrationConfigMapper,
        SecretCryptoService secretCryptoService
    ) {
        this.integrationConfigMapper = integrationConfigMapper;
        this.secretCryptoService = secretCryptoService;
        this.githubAppTokenService = null;
        this.tenantRepositoryResolver = null;
    }

    public GithubIntegrationSettings getSettings() {
        IntegrationConfig config = loadConfig();
        if (config == null) {
            return GithubIntegrationSettings.empty();
        }
        return settings(config, config.getDefaultOwner(), config.getDefaultRepo());
    }

    public GithubIntegrationSettings getSettingsForRepository(String organization, String repository) {
        IntegrationConfig config = loadConfig();
        if (config == null) {
            return GithubIntegrationSettings.empty();
        }
        return settings(config, organization, repository);
    }

    public void markChecked(GithubIntegrationSettings settings, String error) {
        if (settings == null || settings.id() == null) {
            return;
        }
        IntegrationConfig config = new IntegrationConfig();
        config.setId(settings.id());
        config.setLastCheckedAt(LocalDateTime.now());
        config.setLastError(error);
        config.setStatus(error == null ? "CONFIGURED" : "FAILED");
        config.setUpdatedAt(LocalDateTime.now());
        integrationConfigMapper.updateById(config);
    }

    private IntegrationConfig loadConfig() {
        return integrationConfigMapper.selectOne(
            new LambdaQueryWrapper<IntegrationConfig>().eq(IntegrationConfig::getProvider, GITHUB_PROVIDER)
        );
    }

    private GithubIntegrationSettings settings(
        IntegrationConfig config,
        String organization,
        String repository
    ) {
        String token = installationToken(config, organization, repository);
        return new GithubIntegrationSettings(
            config.getProvider(),
            config.getStatus(),
            config.getBaseUrl(),
            token,
            config.getLastError(),
            config.getDefaultOwner(),
            config.getDefaultRepo(),
            config.getId()
        );
    }

    private String installationToken(IntegrationConfig config, String organization, String repository) {
        if (githubAppTokenService == null || !githubAppTokenService.isEnabled()) {
            return secretCryptoService.decrypt(config.getTokenValue());
        }
        if (tenantRepositoryResolver == null) {
            throw new IllegalStateException("GitHub App tenant repository resolver is unavailable");
        }
        TenantRepositoryBinding binding = tenantRepositoryResolver.resolve(organization, repository, null);
        if (binding.githubInstallationId() == null) {
            throw new IllegalStateException("GitHub repository has no App installation mapping");
        }
        return githubAppTokenService.installationToken(config.getBaseUrl(), binding.githubInstallationId());
    }
}
