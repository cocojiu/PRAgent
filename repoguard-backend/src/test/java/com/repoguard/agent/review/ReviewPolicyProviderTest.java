package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.mapper.ReviewPolicyConfigMapper;
import com.repoguard.agent.security.SecretCryptoService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ReviewPolicyProviderTest {

    private final ReviewPolicyConfigMapper reviewPolicyConfigMapper = org.mockito.Mockito.mock(ReviewPolicyConfigMapper.class);
    private final SecretCryptoService secretCryptoService = new SecretCryptoService("test-encryption-key");
    private final ReviewPolicyProvider provider = new ReviewPolicyProvider(
        reviewPolicyConfigMapper,
        secretCryptoService
    );

    @Test
    void getSettingsReturnsDecryptedReviewPolicySettings() {
        ReviewPolicyConfig config = new ReviewPolicyConfig();
        config.setLlmEnabled(true);
        config.setLlmProvider("dashscope");
        config.setModelName("qwen-plus");
        config.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        config.setApiKeyValue(secretCryptoService.encrypt("sk-test"));
        config.setTimeoutSeconds(60);
        config.setTemperature(BigDecimal.valueOf(0.20));
        config.setMaxTokens(4096);
        config.setFallbackToRules(true);
        config.setWorkerConcurrency(2);
        config.setChunkMaxFiles(4);
        config.setChunkMaxLines(450);
        config.setInputTokenPricePerMillion(BigDecimal.valueOf(0.5));
        config.setOutputTokenPricePerMillion(BigDecimal.valueOf(1.5));
        when(reviewPolicyConfigMapper.selectById(1L)).thenReturn(config);

        ReviewPolicySettings settings = provider.getSettings();

        assertThat(settings.exists()).isTrue();
        assertThat(settings.enabled()).isTrue();
        assertThat(settings.readyForLlmReview()).isTrue();
        assertThat(settings.llmProvider()).isEqualTo("dashscope");
        assertThat(settings.modelName()).isEqualTo("qwen-plus");
        assertThat(settings.apiKey()).isEqualTo("sk-test");
        assertThat(settings.workerConcurrency()).isEqualTo(2);
        assertThat(settings.chunkMaxFiles()).isEqualTo(4);
        assertThat(settings.inputTokenPricePerMillion()).isEqualByComparingTo(BigDecimal.valueOf(0.5));
    }

    @Test
    void getSettingsReturnsEmptySettingsWhenPolicyIsMissing() {
        when(reviewPolicyConfigMapper.selectById(1L)).thenReturn(null);

        ReviewPolicySettings settings = provider.getSettings();

        assertThat(settings.exists()).isFalse();
        assertThat(settings.enabled()).isFalse();
        assertThat(settings.readyForLlmReview()).isFalse();
    }
}
