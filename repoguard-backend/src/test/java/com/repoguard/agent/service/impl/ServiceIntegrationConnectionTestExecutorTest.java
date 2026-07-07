package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.dto.ServiceIntegrationConfigRequest;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.security.SecretCryptoService;
import org.junit.jupiter.api.Test;

class ServiceIntegrationConnectionTestExecutorTest {

    private final IntegrationConfigMapper integrationConfigMapper =
        org.mockito.Mockito.mock(IntegrationConfigMapper.class);
    private final SecretCryptoService secretCryptoService = new SecretCryptoService("test-encryption-key");
    private final ConnectionTestConfigFactory configFactory =
        new ConnectionTestConfigFactory(secretCryptoService);
    private final IntegrationConnectionCheckMarker connectionCheckMarker =
        new IntegrationConnectionCheckMarker(integrationConfigMapper);
    private final ServiceIntegrationConnectionTestExecutor executor =
        new ServiceIntegrationConnectionTestExecutor(integrationConfigMapper, configFactory, connectionCheckMarker);

    @Test
    void submittedConfigUsesSavedSecretWithoutPersistingStatus() {
        IntegrationConfig savedConfig = serviceConfig("MYSQL", "mysql-existing-1234");
        savedConfig.setBaseUrl("jdbc:mysql://saved:3306/repoguard");
        when(integrationConfigMapper.selectOne(any())).thenReturn(savedConfig);

        CapturingServiceRunner runner = new CapturingServiceRunner(
            new ConnectionProbeResult(true, "connected", "runtime ok"),
            new ConnectionProbeResult(true, "connected", "configured ok")
        );

        var result = executor.test("MYSQL", new ServiceIntegrationConfigRequest(
            "jdbc:mysql://submitted:3306/repoguard",
            "root",
            "****1234",
            "repoguard"
        ), runner);

        assertThat(result.success()).isTrue();
        assertThat(result.testedConfigSource()).isEqualTo("submitted_config");
        assertThat(runner.savedConfig).isSameAs(savedConfig);
        assertThat(runner.configToProbe.getBaseUrl()).isEqualTo("jdbc:mysql://submitted:3306/repoguard");
        assertThat(secretCryptoService.decrypt(runner.configToProbe.getTokenValue())).isEqualTo("mysql-existing-1234");
        assertThat(runner.transientConfig).isTrue();
        verify(integrationConfigMapper, never()).updateById(any(IntegrationConfig.class));
        verify(integrationConfigMapper, never()).update(any(UpdateWrapper.class));
    }

    @Test
    void savedConfigFailureMarksLatestCheckResult() {
        IntegrationConfig savedConfig = serviceConfig("RABBITMQ", "rabbit-secret-1234");
        when(integrationConfigMapper.selectOne(any())).thenReturn(savedConfig);

        CapturingServiceRunner runner = new CapturingServiceRunner(
            new ConnectionProbeResult(true, "connected", "runtime ok"),
            new ConnectionProbeResult(false, "failed", "submitted failed")
        );

        var result = executor.test("RABBITMQ", null, runner);

        assertThat(result.success()).isFalse();
        assertThat(result.testedConfigSource()).isEqualTo("saved_config");
        assertThat(savedConfig.getStatus()).isEqualTo("FAILED");
        assertThat(savedConfig.getLastError()).isEqualTo("submitted failed");
        assertThat(savedConfig.getLastCheckedAt()).isNotNull();
        verify(integrationConfigMapper).updateById(savedConfig);
    }

    @Test
    void missingRunnerFailsFast() {
        assertThatThrownBy(() -> executor.test("MYSQL", null, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("runner");
    }

    private IntegrationConfig serviceConfig(String provider, String secret) {
        IntegrationConfig config = new IntegrationConfig();
        config.setId(2L);
        config.setProvider(provider);
        config.setStatus("CONFIGURED");
        config.setBaseUrl("jdbc:mysql://localhost:3306/repoguard");
        config.setDefaultOwner("repoguard");
        config.setDefaultRepo("repoguard");
        config.setTokenValue(secret);
        return config;
    }

    private static final class CapturingServiceRunner extends ServiceIntegrationConnectionTestRunner {

        private final ConnectionProbeResult runtimeResult;
        private final ConnectionProbeResult configuredResult;
        private IntegrationConfig savedConfig;
        private IntegrationConfig configToProbe;
        private boolean transientConfig;

        private CapturingServiceRunner(
            ConnectionProbeResult runtimeResult,
            ConnectionProbeResult configuredResult
        ) {
            super(
                "connection test succeeded",
                "runtime connection test succeeded",
                () -> runtimeResult,
                new StubConnectionProbe(configuredResult)
            );
            this.runtimeResult = runtimeResult;
            this.configuredResult = configuredResult;
        }

        @Override
        ConnectionTestResultDto run(
            IntegrationConfig savedConfig,
            IntegrationConfig configToProbe,
            boolean transientConfig,
            java.util.function.BiConsumer<IntegrationConfig, String> markChecked
        ) {
            this.savedConfig = savedConfig;
            this.configToProbe = configToProbe;
            this.transientConfig = transientConfig;
            return super.run(savedConfig, configToProbe, transientConfig, markChecked);
        }
    }

    private record StubConnectionProbe(ConnectionProbeResult result) implements ConnectionProbe<IntegrationConfig> {

        @Override
        public String provider() {
            return "STUB";
        }

        @Override
        public ConnectionProbeResult probe(IntegrationConfig config) {
            return result;
        }
    }
}
