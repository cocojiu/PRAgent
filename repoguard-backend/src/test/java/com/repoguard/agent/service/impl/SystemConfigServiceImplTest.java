package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.dto.GithubIntegrationConfigRequest;
import com.repoguard.agent.dto.ReviewPolicyConfigRequest;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.mapper.ReviewPolicyConfigMapper;
import com.repoguard.agent.security.SecretCryptoService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class SystemConfigServiceImplTest {

    private final IntegrationConfigMapper integrationConfigMapper = org.mockito.Mockito.mock(IntegrationConfigMapper.class);
    private final ReviewPolicyConfigMapper reviewPolicyConfigMapper = org.mockito.Mockito.mock(ReviewPolicyConfigMapper.class);
    private final SecretCryptoService secretCryptoService = new SecretCryptoService("test-encryption-key");
    private final SystemConfigServiceImpl service = new SystemConfigServiceImpl(
        integrationConfigMapper,
        reviewPolicyConfigMapper,
        RestClient.builder(),
        new ObjectMapper(),
        null,
        null,
        secretCryptoService
    );

    @Test
    void updateGithubIntegrationMasksTokenAndStoresNewSecret() {
        IntegrationConfig config = githubConfig("old-token");
        when(integrationConfigMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(config);

        var result = service.updateGithubIntegration(new GithubIntegrationConfigRequest(
            "https://api.github.com",
            "ghp_new_secret_1234",
            "repo-guard-demo",
            "spring-boot-demo"
        ));

        assertThat(config.getTokenValue()).startsWith("enc:v1:");
        assertThat(secretCryptoService.decrypt(config.getTokenValue())).isEqualTo("ghp_new_secret_1234");
        assertThat(config.getStatus()).isEqualTo("CONFIGURED");
        assertThat(result.token()).isEqualTo("****1234");
        verify(integrationConfigMapper).updateById(config);
    }

    @Test
    void updateReviewPolicyKeepsExistingApiKeyWhenMaskedValueIsSubmitted() {
        ReviewPolicyConfig config = reviewPolicyConfig("sk-existing-5678");
        when(reviewPolicyConfigMapper.selectById(1L)).thenReturn(config);

        var result = service.updateReviewPolicy(new ReviewPolicyConfigRequest(
            true,
            "dashscope",
            "qwen-plus",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "****5678",
            90,
            BigDecimal.valueOf(0.30),
            8192,
            true,
            2
        ));

        assertThat(config.getApiKeyValue()).startsWith("enc:v1:");
        assertThat(secretCryptoService.decrypt(config.getApiKeyValue())).isEqualTo("sk-existing-5678");
        assertThat(config.getTimeoutSeconds()).isEqualTo(90);
        assertThat(config.getWorkerConcurrency()).isEqualTo(2);
        assertThat(result.apiKey()).isEqualTo("****5678");
        verify(reviewPolicyConfigMapper).updateById(config);
    }

    @Test
    void updateGithubIntegrationClearsTokenWhenBlankValueIsSubmitted() {
        IntegrationConfig config = githubConfig("old-token");
        when(integrationConfigMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(config);

        var result = service.updateGithubIntegration(new GithubIntegrationConfigRequest(
            "https://api.github.com",
            "",
            "repo-guard-demo",
            "spring-boot-demo"
        ));

        assertThat(config.getTokenValue()).isNull();
        assertThat(config.getStatus()).isEqualTo("NOT_CONFIGURED");
        assertThat(result.token()).isNull();
        verify(integrationConfigMapper).updateById(config);
        verify(integrationConfigMapper, org.mockito.Mockito.times(2)).update(any(UpdateWrapper.class));
    }

    @Test
    void updateReviewPolicyClearsApiKeyWhenBlankValueIsSubmitted() {
        ReviewPolicyConfig config = reviewPolicyConfig("sk-existing-5678");
        when(reviewPolicyConfigMapper.selectById(1L)).thenReturn(config);

        var result = service.updateReviewPolicy(new ReviewPolicyConfigRequest(
            true,
            "dashscope",
            "qwen-plus",
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "",
            90,
            BigDecimal.valueOf(0.30),
            8192,
            true,
            2
        ));

        assertThat(config.getApiKeyValue()).isNull();
        assertThat(result.apiKey()).isNull();
        verify(reviewPolicyConfigMapper).updateById(config);
        verify(reviewPolicyConfigMapper).update(any(UpdateWrapper.class));
    }

    private IntegrationConfig githubConfig(String token) {
        IntegrationConfig config = new IntegrationConfig();
        config.setId(1L);
        config.setProvider("GITHUB");
        config.setStatus("CONFIGURED");
        config.setBaseUrl("https://api.github.com");
        config.setTokenValue(token);
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        return config;
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
