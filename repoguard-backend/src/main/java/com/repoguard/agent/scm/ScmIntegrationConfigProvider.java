package com.repoguard.agent.scm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.security.SecretCryptoService;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Reads tenant-scoped SCM integration settings without exposing plaintext secrets. */
@Service
public class ScmIntegrationConfigProvider {

    private final IntegrationConfigMapper integrationConfigMapper;
    private final SecretCryptoService secretCryptoService;

    public ScmIntegrationConfigProvider(
        IntegrationConfigMapper integrationConfigMapper,
        SecretCryptoService secretCryptoService
    ) {
        this.integrationConfigMapper = Objects.requireNonNull(integrationConfigMapper, "integrationConfigMapper");
        this.secretCryptoService = Objects.requireNonNull(secretCryptoService, "secretCryptoService");
    }

    public ScmIntegrationSettings settings(String provider) {
        String key = normalizeProvider(provider);
        IntegrationConfig config = integrationConfigMapper.selectOne(
            new LambdaQueryWrapper<IntegrationConfig>().eq(IntegrationConfig::getProvider, key)
        );
        return toSettings(key, config);
    }

    public ScmIntegrationSettings settingsForRepository(String provider, String namespace, String repository) {
        ScmIntegrationSettings settings = settings(provider);
        if (settings.defaultNamespace() == null || settings.defaultRepository() == null) {
            return settings;
        }
        if (StringUtils.hasText(namespace) && StringUtils.hasText(repository)
            && settings.defaultNamespace().equalsIgnoreCase(namespace.trim())
            && settings.defaultRepository().equalsIgnoreCase(repository.trim())) {
            return settings;
        }
        return settings;
    }

    private ScmIntegrationSettings toSettings(String provider, IntegrationConfig config) {
        if (config == null) {
            return new ScmIntegrationSettings(provider, "NOT_CONFIGURED", defaultBaseUrl(provider),
                null, null, null, null, null);
        }
        String token = StringUtils.hasText(config.getTokenValue())
            ? secretCryptoService.decrypt(config.getTokenValue())
            : null;
        return new ScmIntegrationSettings(
            provider,
            config.getStatus(),
            config.getBaseUrl(),
            token,
            config.getLastError(),
            config.getDefaultOwner(),
            config.getDefaultRepo(),
            config.getId()
        );
    }

    private String normalizeProvider(String provider) {
        if (!StringUtils.hasText(provider)) {
            throw new IllegalArgumentException("SCM provider is required");
        }
        String key = provider.trim().toUpperCase(Locale.ROOT);
        if (!ListBackedProviderKeys.SUPPORTED.contains(key)) {
            throw new IllegalArgumentException("Unsupported SCM provider: " + provider);
        }
        return key;
    }

    private String defaultBaseUrl(String provider) {
        return switch (provider) {
            case "GITHUB" -> "https://api.github.com";
            case "GITLAB" -> "https://gitlab.com";
            case "GITEE" -> "https://gitee.com";
            case "BITBUCKET" -> "https://api.bitbucket.org";
            default -> "";
        };
    }

    private static final class ListBackedProviderKeys {
        private static final java.util.Set<String> SUPPORTED = java.util.Set.of(
            "GITHUB", "GITLAB", "GITEE", "BITBUCKET"
        );

        private ListBackedProviderKeys() {
        }
    }
}
