package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.security.SecretCryptoService;
import org.junit.jupiter.api.Test;

class GithubIntegrationProviderTest {

    private final IntegrationConfigMapper integrationConfigMapper = org.mockito.Mockito.mock(IntegrationConfigMapper.class);
    private final SecretCryptoService secretCryptoService = new SecretCryptoService("test-encryption-key");
    private final GithubIntegrationProvider provider = new GithubIntegrationProvider(
        integrationConfigMapper,
        secretCryptoService
    );

    @Test
    void getSettingsReturnsDecryptedGithubConfiguration() {
        IntegrationConfig config = new IntegrationConfig();
        config.setProvider("GITHUB");
        config.setStatus("CONFIGURED");
        config.setBaseUrl("https://api.github.com");
        config.setTokenValue(secretCryptoService.encrypt("ghp_test"));
        when(integrationConfigMapper.selectOne(any())).thenReturn(config);

        GithubIntegrationSettings settings = provider.getSettings();

        assertThat(settings.provider()).isEqualTo("GITHUB");
        assertThat(settings.status()).isEqualTo("CONFIGURED");
        assertThat(settings.baseUrl()).isEqualTo("https://api.github.com");
        assertThat(settings.token()).isEqualTo("ghp_test");
    }

    @Test
    void getSettingsReturnsEmptySettingsWhenConfigurationIsMissing() {
        when(integrationConfigMapper.selectOne(any())).thenReturn(null);

        GithubIntegrationSettings settings = provider.getSettings();

        assertThat(settings.provider()).isEqualTo("GITHUB");
        assertThat(settings.status()).isNull();
        assertThat(settings.baseUrl()).isNull();
        assertThat(settings.token()).isNull();
    }
}
