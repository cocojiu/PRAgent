package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.dto.GithubIntegrationConfigRequest;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.security.SecretCryptoService;
import org.junit.jupiter.api.Test;

class GithubIntegrationConnectionTestExecutorTest {

    private final IntegrationConfigMapper integrationConfigMapper =
        org.mockito.Mockito.mock(IntegrationConfigMapper.class);
    private final SecretCryptoService secretCryptoService = new SecretCryptoService("test-encryption-key");
    private final ConnectionTestConfigFactory configFactory =
        new ConnectionTestConfigFactory(secretCryptoService);
    private final IntegrationConnectionCheckMarker connectionCheckMarker =
        new IntegrationConnectionCheckMarker(integrationConfigMapper);
    private final GithubIntegrationConnectionTestExecutor executor =
        new GithubIntegrationConnectionTestExecutor(integrationConfigMapper, configFactory, connectionCheckMarker);

    @Test
    void submittedConfigUsesSavedTokenWithoutPersistingStatus() {
        IntegrationConfig savedConfig = githubConfig("ghp_saved_1234");
        savedConfig.setBaseUrl("https://api.github.com");
        when(integrationConfigMapper.selectOne(any())).thenReturn(savedConfig);

        CapturingGithubRunner runner = new CapturingGithubRunner(
            new ConnectionProbeResult(true, "connected", "GitHub connection test succeeded"),
            null
        );

        var result = executor.test("GITHUB", new GithubIntegrationConfigRequest(
            "http://127.0.0.1:8080",
            "****1234",
            " octocat ",
            " api "
        ), runner);

        assertThat(result.success()).isTrue();
        assertThat(runner.configToProbe.getBaseUrl()).isEqualTo("http://127.0.0.1:8080");
        assertThat(secretCryptoService.decrypt(runner.configToProbe.getTokenValue())).isEqualTo("ghp_saved_1234");
        assertThat(runner.configToProbe.getDefaultOwner()).isEqualTo("octocat");
        assertThat(runner.configToProbe.getDefaultRepo()).isEqualTo("api");
        assertThat(runner.transientConfig).isTrue();
        verify(integrationConfigMapper, never()).updateById(any(IntegrationConfig.class));
        verify(integrationConfigMapper, never()).update(any(UpdateWrapper.class));
    }

    @Test
    void savedConfigFailureMarksLatestCheckResult() {
        IntegrationConfig savedConfig = githubConfig("ghp_saved_1234");
        when(integrationConfigMapper.selectOne(any())).thenReturn(savedConfig);

        CapturingGithubRunner runner = new CapturingGithubRunner(null, new IllegalStateException("bad token"));

        var result = executor.test("GITHUB", null, runner);

        assertThat(result.success()).isFalse();
        assertThat(savedConfig.getStatus()).isEqualTo("FAILED");
        assertThat(savedConfig.getLastError()).isEqualTo("bad token");
        assertThat(savedConfig.getLastCheckedAt()).isNotNull();
        verify(integrationConfigMapper).update(org.mockito.ArgumentMatchers.isNull(), any(UpdateWrapper.class));
    }

    @Test
    void missingRunnerFailsFast() {
        assertThatThrownBy(() -> executor.test("GITHUB", null, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("runner");
    }

    private IntegrationConfig githubConfig(String token) {
        IntegrationConfig config = new IntegrationConfig();
        config.setId(1L);
        config.setProvider("GITHUB");
        config.setStatus("CONFIGURED");
        config.setBaseUrl("https://api.github.com");
        config.setTokenValue(secretCryptoService.encrypt(token));
        config.setUpdatedAt(java.time.LocalDateTime.parse("2026-07-13T12:00:00"));
        return config;
    }

    private static final class CapturingGithubRunner extends GithubIntegrationConnectionTestRunner {

        private IntegrationConfig configToProbe;
        private boolean transientConfig;

        private CapturingGithubRunner(ConnectionProbeResult result, RuntimeException failure) {
            super(new StubGithubProbe(result, failure));
        }

        @Override
        ConnectionTestResultDto run(
            IntegrationConfig configToProbe,
            boolean transientConfig,
            java.util.function.BiConsumer<IntegrationConfig, String> markChecked
        ) {
            this.configToProbe = configToProbe;
            this.transientConfig = transientConfig;
            return super.run(configToProbe, transientConfig, markChecked);
        }
    }

    private record StubGithubProbe(ConnectionProbeResult result, RuntimeException failure)
        implements ConnectionProbe<IntegrationConfig> {

        @Override
        public String provider() {
            return "GITHUB";
        }

        @Override
        public ConnectionProbeResult probe(IntegrationConfig config) {
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }
}
