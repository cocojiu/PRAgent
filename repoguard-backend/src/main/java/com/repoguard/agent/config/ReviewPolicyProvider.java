package com.repoguard.agent.config;

import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.mapper.ReviewPolicyConfigMapper;
import com.repoguard.agent.security.SecretCryptoService;
import org.springframework.stereotype.Service;

@Service
public class ReviewPolicyProvider {

    private static final long DEFAULT_POLICY_ID = 1L;

    private final ReviewPolicyConfigMapper reviewPolicyConfigMapper;
    private final SecretCryptoService secretCryptoService;

    public ReviewPolicyProvider(
        ReviewPolicyConfigMapper reviewPolicyConfigMapper,
        SecretCryptoService secretCryptoService
    ) {
        this.reviewPolicyConfigMapper = reviewPolicyConfigMapper;
        this.secretCryptoService = secretCryptoService;
    }

    public ReviewPolicySettings getSettings() {
        ReviewPolicyConfig config = reviewPolicyConfigMapper.selectById(DEFAULT_POLICY_ID);
        if (config == null) {
            return ReviewPolicySettings.empty();
        }
        return new ReviewPolicySettings(
            true,
            config.getLlmEnabled(),
            config.getLlmProvider(),
            config.getModelName(),
            config.getBaseUrl(),
            secretCryptoService.decrypt(config.getApiKeyValue()),
            config.getTimeoutSeconds(),
            config.getTemperature(),
            config.getMaxTokens(),
            config.getFallbackToRules(),
            config.getWorkerConcurrency()
        );
    }
}
