package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.dto.ReviewPolicyConfigRequest;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.mapper.ReviewPolicyConfigMapper;
import com.repoguard.agent.security.SecretCryptoService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ReviewPolicyConnectionTestExecutorTest {

    private final ReviewPolicyConfigMapper reviewPolicyConfigMapper =
        org.mockito.Mockito.mock(ReviewPolicyConfigMapper.class);
    private final SecretCryptoService secretCryptoService = new SecretCryptoService("test-encryption-key");
    private final ConnectionTestConfigFactory configFactory =
        new ConnectionTestConfigFactory(secretCryptoService);
    private final ReviewPolicyConnectionTestExecutor executor =
        new ReviewPolicyConnectionTestExecutor(reviewPolicyConfigMapper, configFactory);

    @Test
    void savedConfigUsesDefaultReviewPolicyId() {
        ReviewPolicyConfig savedConfig = reviewPolicyConfig("sk_saved");
        when(reviewPolicyConfigMapper.selectById(1L)).thenReturn(savedConfig);
        CapturingLlmRunner runner = new CapturingLlmRunner(
            new ConnectionProbeResult(true, "connected", "LLM connection test succeeded")
        );

        var result = executor.test(null, runner);

        assertThat(result.success()).isTrue();
        assertThat(runner.configToProbe).isSameAs(savedConfig);
    }

    @Test
    void submittedConfigUsesSavedApiKeyWhenMasked() {
        ReviewPolicyConfig savedConfig = reviewPolicyConfig("sk_saved");
        when(reviewPolicyConfigMapper.selectById(1L)).thenReturn(savedConfig);
        CapturingLlmRunner runner = new CapturingLlmRunner(
            new ConnectionProbeResult(true, "connected", "LLM connection test succeeded")
        );

        var result = executor.test(reviewPolicyRequest("****aved"), runner);

        assertThat(result.success()).isTrue();
        assertThat(runner.configToProbe).isNotSameAs(savedConfig);
        assertThat(runner.configToProbe.getBaseUrl()).isEqualTo("https://dashscope.example/v1");
        assertThat(secretCryptoService.decrypt(runner.configToProbe.getApiKeyValue())).isEqualTo("sk_saved");
        assertThat(runner.configToProbe.getChunkMaxFiles()).isEqualTo(4);
    }

    @Test
    void missingRunnerFailsFast() {
        assertThatThrownBy(() -> executor.test(null, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("runner");
    }

    private ReviewPolicyConfig reviewPolicyConfig(String apiKey) {
        ReviewPolicyConfig config = new ReviewPolicyConfig();
        config.setId(1L);
        config.setLlmEnabled(true);
        config.setLlmProvider("dashscope");
        config.setModelName("qwen-plus");
        config.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        config.setApiKeyValue(secretCryptoService.encrypt(apiKey));
        return config;
    }

    private ReviewPolicyConfigRequest reviewPolicyRequest(String apiKey) {
        return new ReviewPolicyConfigRequest(
            true,
            " dashscope ",
            " qwen-plus ",
            " https://dashscope.example/v1 ",
            apiKey,
            60,
            BigDecimal.valueOf(0.2),
            4096,
            true,
            2,
            6,
            700,
            4,
            450,
            BigDecimal.valueOf(0.0010),
            BigDecimal.valueOf(0.0020)
        );
    }

    private static final class CapturingLlmRunner extends LlmReviewPolicyConnectionTestRunner {

        private ReviewPolicyConfig configToProbe;

        private CapturingLlmRunner(ConnectionProbeResult result) {
            super(new StubLlmProbe(result));
        }

        @Override
        ConnectionTestResultDto run(ReviewPolicyConfig configToProbe) {
            this.configToProbe = configToProbe;
            return super.run(configToProbe);
        }
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
