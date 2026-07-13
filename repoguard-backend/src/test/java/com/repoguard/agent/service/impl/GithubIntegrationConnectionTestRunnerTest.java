package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.IntegrationConfig;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;

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
    void runMarksUnhealthySavedResultAsFailure() {
        IntegrationConfig config = githubConfig();
        GithubIntegrationConnectionTestRunner runner = new GithubIntegrationConnectionTestRunner(
            new StubGithubProbe(new ConnectionProbeResult(false, "failed", "token is missing"), null)
        );
        AtomicReference<String> markedError = new AtomicReference<>();

        var result = runner.run(config, false, (markedConfig, error) -> markedError.set(error));

        assertThat(result.success()).isFalse();
        assertThat(markedError).hasValue("token is missing");
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

    @Test
    void runClassifiesGithubHttpFailureWithRateLimitDiagnostics() {
        IntegrationConfig config = githubConfig();
        GithubIntegrationConnectionTestRunner runner = new GithubIntegrationConnectionTestRunner(
            new StubGithubProbe(null, githubRateLimitFailure())
        );
        AtomicReference<String> markedError = new AtomicReference<>();

        var result = runner.run(config, false, (markedConfig, error) -> markedError.set(error));

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.message()).contains(
            "category=github_rate_limited",
            "retryable=true",
            "status=429",
            "retryAfter=60",
            "rateLimitRemaining=0",
            "rateLimitReset=1763456789",
            "responseBody={\"message\":\"API rate limit exceeded\",\"token\":\""
        );
        assertThat(result.message()).hasSizeLessThanOrEqualTo(240).endsWith("...");
        assertThat(result.message()).doesNotContain("raw-token-value");
        assertThat(markedError.get()).isEqualTo(result.message());
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

    private RestClientResponseException githubRateLimitFailure() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "60");
        headers.add("X-RateLimit-Remaining", "0");
        headers.add("X-RateLimit-Reset", "1763456789");
        String body = "{\"message\":\"API rate limit exceeded\",\"token\":\"raw-token-value\"}";
        return new RestClientResponseException(
            "API rate limit exceeded",
            429,
            "Too Many Requests",
            headers,
            body.getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8
        );
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
