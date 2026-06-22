package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.IntegrationConfig;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GithubIntegrationConnectionTestRunnerTest {

    @Test
    void runReportsMissingConfig() {
        GithubIntegrationConnectionTestRunner runner = new GithubIntegrationConnectionTestRunner(
            new StubGithubProbe(new ConnectionProbeResult(true, "connected", "ok"), null)
        );

        var result = runner.run(null, false, (config, error) -> { });

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.message()).isEqualTo("GitHub integration is not configured");
        assertThat(result.checkedAt()).isNotBlank();
    }

    @Test
    void runMarksSavedConfigCheckedOnSuccess() {
        IntegrationConfig config = githubConfig();
        GithubIntegrationConnectionTestRunner runner = new GithubIntegrationConnectionTestRunner(
            new StubGithubProbe(new ConnectionProbeResult(true, "connected", "GitHub connection test succeeded"), null)
        );
        AtomicReference<String> markedError = new AtomicReference<>("not-called");

        var result = runner.run(config, false, (markedConfig, error) -> markedError.set(error));

        assertThat(result.success()).isTrue();
        assertThat(result.status()).isEqualTo("connected");
        assertThat(markedError).hasValue(null);
    }

    @Test
    void runDoesNotMarkSubmittedConfigChecked() {
        IntegrationConfig config = githubConfig();
        GithubIntegrationConnectionTestRunner runner = new GithubIntegrationConnectionTestRunner(
            new StubGithubProbe(new ConnectionProbeResult(true, "connected", "GitHub connection test succeeded"), null)
        );
        AtomicReference<String> markedError = new AtomicReference<>("not-called");

        var result = runner.run(config, true, (markedConfig, error) -> markedError.set(error));

        assertThat(result.success()).isTrue();
        assertThat(markedError).hasValue("not-called");
    }

    @Test
    void runMarksSavedConfigCheckedOnFailure() {
        IntegrationConfig config = githubConfig();
        GithubIntegrationConnectionTestRunner runner = new GithubIntegrationConnectionTestRunner(
            new StubGithubProbe(null, new IllegalStateException("boom"))
        );
        AtomicReference<String> markedError = new AtomicReference<>();

        var result = runner.run(config, false, (markedConfig, error) -> markedError.set(error));

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.message()).isEqualTo("boom");
        assertThat(markedError).hasValue("boom");
    }

    private IntegrationConfig githubConfig() {
        IntegrationConfig config = new IntegrationConfig();
        config.setId(1L);
        config.setProvider("GITHUB");
        config.setStatus("CONFIGURED");
        config.setBaseUrl("https://api.github.com");
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        return config;
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
