package com.repoguard.agent.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.security.SecretCryptoService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
        config.setLastError("last failure");
        config.setDefaultOwner("octocat");
        config.setDefaultRepo("api");
        config.setId(7L);
        when(integrationConfigMapper.selectOne(any())).thenReturn(config);

        GithubIntegrationSettings settings = provider.getSettings();

        assertThat(settings.provider()).isEqualTo("GITHUB");
        assertThat(settings.status()).isEqualTo("CONFIGURED");
        assertThat(settings.baseUrl()).isEqualTo("https://api.github.com");
        assertThat(settings.token()).isEqualTo("ghp_test");
        assertThat(settings.lastError()).isEqualTo("last failure");
        assertThat(settings.defaultOwner()).isEqualTo("octocat");
        assertThat(settings.defaultRepo()).isEqualTo("api");
        assertThat(settings.id()).isEqualTo(7L);
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

    @Test
    void markCheckedUpdatesOnlyRuntimeHealthFields() {
        GithubIntegrationSettings settings = new GithubIntegrationSettings(
            "GITHUB",
            "CONFIGURED",
            "https://api.github.com",
            "ghp_test",
            null,
            "octocat",
            "api",
            7L
        );

        provider.markChecked(settings, "bad token");

        ArgumentCaptor<IntegrationConfig> configCaptor = ArgumentCaptor.forClass(IntegrationConfig.class);
        verify(integrationConfigMapper).updateById(configCaptor.capture());
        IntegrationConfig updatedConfig = configCaptor.getValue();
        assertThat(updatedConfig.getId()).isEqualTo(7L);
        assertThat(updatedConfig.getStatus()).isEqualTo("FAILED");
        assertThat(updatedConfig.getLastError()).isEqualTo("bad token");
        assertThat(updatedConfig.getLastCheckedAt()).isNotNull();
        assertThat(updatedConfig.getUpdatedAt()).isNotNull();
    }
}
