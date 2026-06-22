package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.IntegrationConfig;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ServiceIntegrationConnectionTestRunnerTest {

    @Test
    void runUsesSubmittedConfigWithoutMarkingSavedConfigChecked() {
        ServiceIntegrationConnectionTestRunner runner = runner(
            new ConnectionProbeResult(null, "unavailable", "Runtime is unavailable"),
            new ConnectionProbeResult(false, "failed", "submitted failed")
        );
        IntegrationConfig savedConfig = serviceConfig("CONFIGURED", null);
        IntegrationConfig submittedConfig = serviceConfig("CONFIGURED", null);
        AtomicReference<String> markedError = new AtomicReference<>("not-called");

        var result = runner.run(savedConfig, submittedConfig, true, (config, error) -> markedError.set(error));

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.message()).isEqualTo("submitted failed");
        assertThat(result.testedConfigSource()).isEqualTo("submitted_config");
        assertThat(result.runtimeHealthy()).isNull();
        assertThat(result.runtimeConnectionStatus()).isEqualTo("unavailable");
        assertThat(result.savedConfigHealthy()).isTrue();
        assertThat(result.savedConfigStatus()).isEqualTo("configured");
        assertThat(result.mismatch()).isNull();
        assertThat(markedError).hasValue("not-called");
    }

    @Test
    void runMarksSavedConfigCheckedWhenSavedConfigIsProbed() {
        ServiceIntegrationConnectionTestRunner runner = runner(
            new ConnectionProbeResult(true, "connected", "runtime ok"),
            new ConnectionProbeResult(false, "failed", "saved failed")
        );
        IntegrationConfig savedConfig = serviceConfig("CONFIGURED", null);
        AtomicReference<String> markedError = new AtomicReference<>();

        var result = runner.run(savedConfig, savedConfig, false, (config, error) -> markedError.set(error));

        assertThat(result.success()).isFalse();
        assertThat(result.testedConfigSource()).isEqualTo("saved_config");
        assertThat(result.runtimeHealthy()).isTrue();
        assertThat(result.savedConfigHealthy()).isFalse();
        assertThat(result.mismatch()).isTrue();
        assertThat(markedError).hasValue("saved failed");
    }

    @Test
    void runFallsBackToRuntimeConfigWhenSavedConfigIsMissing() {
        ServiceIntegrationConnectionTestRunner runner = runner(
            new ConnectionProbeResult(false, "failed", "runtime failed"),
            new ConnectionProbeResult(true, "connected", "configured ok")
        );

        var result = runner.run(null, null, false, (config, error) -> { });

        assertThat(result.success()).isFalse();
        assertThat(result.testedConfigSource()).isEqualTo("runtime_config");
        assertThat(result.runtimeHealthy()).isFalse();
        assertThat(result.savedConfigHealthy()).isNull();
        assertThat(result.savedConfigStatus()).isEqualTo("not_configured");
        assertThat(result.message()).isEqualTo("runtime failed");
    }

    private ServiceIntegrationConnectionTestRunner runner(
        ConnectionProbeResult runtimeResult,
        ConnectionProbeResult configuredResult
    ) {
        return new ServiceIntegrationConnectionTestRunner(
            "Configured connection succeeded",
            "Runtime connection succeeded",
            () -> runtimeResult,
            new StubConnectionProbe(configuredResult)
        );
    }

    private IntegrationConfig serviceConfig(String status, String lastError) {
        IntegrationConfig config = new IntegrationConfig();
        config.setId(1L);
        config.setProvider("MYSQL");
        config.setStatus(status);
        config.setLastError(lastError);
        config.setBaseUrl("jdbc:mysql://localhost:3306/repoguard");
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        return config;
    }

    private record StubConnectionProbe(ConnectionProbeResult result) implements ConnectionProbe<IntegrationConfig> {

        @Override
        public String provider() {
            return "TEST";
        }

        @Override
        public ConnectionProbeResult probe(IntegrationConfig config) {
            return result;
        }
    }
}
