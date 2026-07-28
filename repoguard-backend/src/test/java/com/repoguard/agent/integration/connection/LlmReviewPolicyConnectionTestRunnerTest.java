package com.repoguard.agent.integration.connection;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.ReviewPolicyConfig;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class LlmReviewPolicyConnectionTestRunnerTest {

    @Test
    void runReportsMissingConfig() {
        LlmReviewPolicyConnectionTestRunner runner = new LlmReviewPolicyConnectionTestRunner(
            new StubLlmProbe(new ConnectionProbeResult(true, "connected", "ok"))
        );

        var result = runner.run(null);

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.message()).isEqualTo("LLM config is not configured");
        assertThat(result.checkedAt()).isNotBlank();
    }

    @Test
    void runReturnsProbeSuccessResult() {
        LlmReviewPolicyConnectionTestRunner runner = new LlmReviewPolicyConnectionTestRunner(
            new StubLlmProbe(new ConnectionProbeResult(true, "connected", "LLM connection test succeeded"))
        );

        var result = runner.run(reviewPolicyConfig());

        assertThat(result.success()).isTrue();
        assertThat(result.status()).isEqualTo("connected");
        assertThat(result.message()).isEqualTo("LLM connection test succeeded");
        assertThat(result.checkedAt()).isNotBlank();
    }

    @Test
    void runReturnsProbeFailureResult() {
        LlmReviewPolicyConnectionTestRunner runner = new LlmReviewPolicyConnectionTestRunner(
            new StubLlmProbe(new ConnectionProbeResult(false, "failed", "LLM response was received but could not be parsed as review JSON: boom"))
        );

        var result = runner.run(reviewPolicyConfig());

        assertThat(result.success()).isFalse();
        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.message()).contains("could not be parsed as review JSON");
        assertThat(result.checkedAt()).isNotBlank();
    }

    private ReviewPolicyConfig reviewPolicyConfig() {
        ReviewPolicyConfig config = new ReviewPolicyConfig();
        config.setId(1L);
        config.setLlmEnabled(true);
        config.setLlmProvider("dashscope");
        config.setModelName("qwen-plus");
        config.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        return config;
    }

    private record StubLlmProbe(ConnectionProbeResult result) implements ConnectionProbe<ReviewPolicyConfig> {

        @Override
        public String provider() {
            return "LLM";
        }

        @Override
        public ConnectionProbeResult probe(ReviewPolicyConfig config) {
            return result;
        }
    }
}
