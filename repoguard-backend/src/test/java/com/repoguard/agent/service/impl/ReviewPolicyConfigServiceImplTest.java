package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.cache.CacheEvictionService;
import com.repoguard.agent.dto.ReviewPolicyConfigRequest;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.mapper.ReviewPolicyConfigMapper;
import com.repoguard.agent.security.SecretCryptoService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ReviewPolicyConfigServiceImplTest {

    private final ReviewPolicyConfigMapper reviewPolicyConfigMapper = org.mockito.Mockito.mock(ReviewPolicyConfigMapper.class);
    private final SecretCryptoService secretCryptoService = new SecretCryptoService("test-encryption-key");
    private final CacheEvictionService cacheEvictionService = org.mockito.Mockito.mock(CacheEvictionService.class);
    private final ReviewPolicyConfigServiceImpl service = new ReviewPolicyConfigServiceImpl(
        reviewPolicyConfigMapper,
        secretCryptoService,
        cacheEvictionService
    );

    @Test
    void getReviewPolicyCreatesDefaultsWhenMissing() {
        var result = service.getReviewPolicy();

        assertThat(result.llmEnabled()).isTrue();
        assertThat(result.llmProvider()).isEqualTo("dashscope");
        assertThat(result.modelName()).isEqualTo("qwen-plus");
        assertThat(result.baseUrl()).isEqualTo("https://dashscope.aliyuncs.com/compatible-mode/v1");
        assertThat(result.timeoutSeconds()).isEqualTo(60);
        assertThat(result.temperature()).isEqualByComparingTo("0.20");
        assertThat(result.maxTokens()).isEqualTo(4096);
        assertThat(result.workerConcurrency()).isEqualTo(1);
        assertThat(result.chunkFileThreshold()).isEqualTo(6);
        assertThat(result.chunkLineThreshold()).isEqualTo(700);
        assertThat(result.chunkMaxFiles()).isEqualTo(4);
        assertThat(result.chunkMaxLines()).isEqualTo(450);
        assertThat(result.inputTokenPricePerMillion()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.outputTokenPricePerMillion()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(reviewPolicyConfigMapper).insert(any(ReviewPolicyConfig.class));
    }

    @Test
    void updateReviewPolicyKeepsExistingApiKeyWhenMaskedValueIsSubmitted() {
        ReviewPolicyConfig config = reviewPolicyConfig(secretCryptoService.encrypt("sk-existing-5678"));
        when(reviewPolicyConfigMapper.selectById(1L)).thenReturn(config);

        var result = service.updateReviewPolicy(request("****5678"));

        assertThat(config.getApiKeyValue()).startsWith("enc:v3:local:");
        assertThat(secretCryptoService.decrypt(config.getApiKeyValue())).isEqualTo("sk-existing-5678");
        assertThat(config.getTimeoutSeconds()).isEqualTo(90);
        assertThat(config.getWorkerConcurrency()).isEqualTo(1);
        assertThat(config.getChunkFileThreshold()).isEqualTo(6);
        assertThat(config.getInputTokenPricePerMillion()).isEqualByComparingTo("0.50");
        assertThat(result.apiKey()).isEqualTo("****5678");
        assertThat(result.chunkLineThreshold()).isEqualTo(700);
        assertThat(result.outputTokenPricePerMillion()).isEqualByComparingTo("1.50");
        verify(reviewPolicyConfigMapper).updateById(config);
        verify(cacheEvictionService).evictDashboardOverviewCompatibility();
    }

    @Test
    void updateReviewPolicyClearsApiKeyWhenBlankValueIsSubmitted() {
        ReviewPolicyConfig config = reviewPolicyConfig(secretCryptoService.encrypt("sk-existing-5678"));
        when(reviewPolicyConfigMapper.selectById(1L)).thenReturn(config);

        var result = service.updateReviewPolicy(request(""));

        assertThat(config.getApiKeyValue()).isNull();
        assertThat(result.apiKey()).isNull();
        verify(reviewPolicyConfigMapper).updateById(config);
        verify(reviewPolicyConfigMapper).update(any());
    }

    @Test
    void getReviewPolicyReportsDecryptFailedWithoutThrowing() {
        ReviewPolicyConfig config = reviewPolicyConfig("enc:v2:local:not-a-real-payload");
        when(reviewPolicyConfigMapper.selectById(1L)).thenReturn(config);

        var result = service.getReviewPolicy();

        assertThat(result.apiKey()).isNull();
        assertThat(result.secretStatus()).isEqualTo("decrypt_failed");
    }

    @Test
    void updateReviewPolicyCanReplaceMismatchedExistingApiKeyWithoutDecryptingIt() {
        ReviewPolicyConfig config = reviewPolicyConfig("enc:v2:old-key:not-a-real-payload");
        when(reviewPolicyConfigMapper.selectById(1L)).thenReturn(config);

        var result = service.updateReviewPolicy(request("sk-repaired-9999"));

        assertThat(secretCryptoService.decrypt(config.getApiKeyValue())).isEqualTo("sk-repaired-9999");
        assertThat(result.apiKey()).isEqualTo("****9999");
        assertThat(result.secretStatus()).isEqualTo("configured");
        verify(reviewPolicyConfigMapper).updateById(config);
    }

    @Test
    void updateReviewPolicyPreservesDamagedExistingApiKeyWhenMaskedValueIsSubmitted() {
        ReviewPolicyConfig config = reviewPolicyConfig("enc:v2:local:not-a-real-payload");
        when(reviewPolicyConfigMapper.selectById(1L)).thenReturn(config);

        var result = service.updateReviewPolicy(request("****"));

        assertThat(config.getApiKeyValue()).isEqualTo("enc:v2:local:not-a-real-payload");
        assertThat(config.getTimeoutSeconds()).isEqualTo(90);
        assertThat(result.apiKey()).isNull();
        assertThat(result.secretStatus()).isEqualTo("decrypt_failed");
        verify(reviewPolicyConfigMapper).updateById(config);
    }

    @Test
    void updateReviewPolicyStoresNewApiKeyAndTrimsOptionalBaseUrl() {
        ReviewPolicyConfig config = reviewPolicyConfig(secretCryptoService.encrypt("sk-existing-5678"));
        when(reviewPolicyConfigMapper.selectById(1L)).thenReturn(config);

        var result = service.updateReviewPolicy(new ReviewPolicyConfigRequest(
            true,
            "dashscope",
            "qwen-plus",
            "   ",
            "sk-new-secret-9999",
            90,
            BigDecimal.valueOf(0.30),
            8192,
            true,
            2,
            6,
            700,
            4,
            450,
            BigDecimal.valueOf(0.50),
            BigDecimal.valueOf(1.50)
        ));

        assertThat(config.getBaseUrl()).isNull();
        assertThat(secretCryptoService.decrypt(config.getApiKeyValue())).isEqualTo("sk-new-secret-9999");
        assertThat(result.apiKey()).isEqualTo("****9999");
        verify(reviewPolicyConfigMapper).updateById(config);
    }

    private ReviewPolicyConfigRequest request(String apiKey) {
        return new ReviewPolicyConfigRequest(
            true,
            "dashscope",
            "qwen-plus",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            apiKey,
            90,
            BigDecimal.valueOf(0.30),
            8192,
            true,
            2,
            6,
            700,
            4,
            450,
            BigDecimal.valueOf(0.50),
            BigDecimal.valueOf(1.50)
        );
    }

    private ReviewPolicyConfig reviewPolicyConfig(String apiKey) {
        ReviewPolicyConfig config = new ReviewPolicyConfig();
        config.setId(1L);
        config.setLlmEnabled(true);
        config.setLlmProvider("dashscope");
        config.setModelName("qwen-plus");
        config.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        config.setApiKeyValue(apiKey);
        config.setTimeoutSeconds(60);
        config.setTemperature(BigDecimal.valueOf(0.20));
        config.setMaxTokens(4096);
        config.setFallbackToRules(true);
        config.setWorkerConcurrency(1);
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        return config;
    }
}
