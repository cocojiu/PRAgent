package com.repoguard.agent.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.security.SecretCryptoService;
import org.junit.jupiter.api.Test;

class ScmIntegrationConfigProviderTest {

    private final IntegrationConfigMapper mapper = mock(IntegrationConfigMapper.class);
    private final SecretCryptoService crypto = mock(SecretCryptoService.class);
    private final ScmIntegrationConfigProvider provider = new ScmIntegrationConfigProvider(mapper, crypto);

    @Test
    void returnsProviderSpecificDefaultsWhenConfigIsMissing() {
        when(mapper.selectOne(any())).thenReturn(null);

        assertThat(provider.settings(" github ").baseUrl()).isEqualTo("https://api.github.com");
        assertThat(provider.settings("GITLAB").baseUrl()).isEqualTo("https://gitlab.com");
        assertThat(provider.settings("GITEE").baseUrl()).isEqualTo("https://gitee.com");
        assertThat(provider.settings("BITBUCKET").baseUrl()).isEqualTo("https://api.bitbucket.org");
        assertThat(provider.settings("GITLAB").status()).isEqualTo("NOT_CONFIGURED");
    }

    @Test
    void decryptsConfiguredTokenAndPreservesRepositoryDefaults() {
        IntegrationConfig config = new IntegrationConfig();
        config.setId(9L);
        config.setProvider("GITLAB");
        config.setStatus("CONFIGURED");
        config.setBaseUrl("https://gitlab.example");
        config.setTokenValue("ciphertext");
        config.setDefaultOwner("acme");
        config.setDefaultRepo("widgets");
        config.setLastError("previous warning");
        when(mapper.selectOne(any())).thenReturn(config);
        when(crypto.decrypt("ciphertext")).thenReturn("decrypted-token");

        ScmIntegrationSettings settings = provider.settingsForRepository("gitlab", "acme", "widgets");

        assertThat(settings).isEqualTo(new ScmIntegrationSettings(
            "GITLAB", "CONFIGURED", "https://gitlab.example", "decrypted-token", "previous warning",
            "acme", "widgets", 9L
        ));
        assertThat(provider.settingsForRepository("gitlab", "other", "repo")).isEqualTo(settings);
        verify(crypto, org.mockito.Mockito.times(2)).decrypt("ciphertext");
    }

    @Test
    void leavesBlankTokenUndecryptedAndValidatesProviderKey() {
        IntegrationConfig config = new IntegrationConfig();
        config.setProvider("GITEE");
        config.setStatus("CONFIGURED");
        config.setTokenValue(" ");
        when(mapper.selectOne(any())).thenReturn(config);

        assertThat(provider.settings("GITEE").token()).isNull();
        assertThat(provider.settingsForRepository("GITEE", null, null).status()).isEqualTo("CONFIGURED");
        assertThatThrownBy(() -> provider.settings(" "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("required");
        assertThatThrownBy(() -> provider.settings("SVN"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported SCM provider");
    }
}
