package com.repoguard.agent.review;

import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.mapper.ReviewPolicyConfigMapper;
import com.repoguard.agent.security.SecretCryptoService;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ReviewPolicyProvider {

    private static final long DEFAULT_POLICY_ID = 1L;

    private final ReviewPolicyConfigMapper reviewPolicyConfigMapper;
    private final SecretCryptoService secretCryptoService;
    private final ReviewStrategyReleaseProvider strategyReleaseProvider;

    @Autowired
    public ReviewPolicyProvider(
        ReviewPolicyConfigMapper reviewPolicyConfigMapper,
        SecretCryptoService secretCryptoService,
        ReviewStrategyReleaseProvider strategyReleaseProvider
    ) {
        this.reviewPolicyConfigMapper = Objects.requireNonNull(reviewPolicyConfigMapper, "reviewPolicyConfigMapper");
        this.secretCryptoService = Objects.requireNonNull(secretCryptoService, "secretCryptoService");
        this.strategyReleaseProvider = Objects.requireNonNull(strategyReleaseProvider, "strategyReleaseProvider");
    }

    public ReviewPolicyProvider(
        ReviewPolicyConfigMapper reviewPolicyConfigMapper,
        SecretCryptoService secretCryptoService
    ) {
        this.reviewPolicyConfigMapper = Objects.requireNonNull(reviewPolicyConfigMapper, "reviewPolicyConfigMapper");
        this.secretCryptoService = Objects.requireNonNull(secretCryptoService, "secretCryptoService");
        this.strategyReleaseProvider = null;
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
            config.getWorkerConcurrency(),
            config.getChunkFileThreshold(),
            config.getChunkLineThreshold(),
            config.getChunkMaxFiles(),
            config.getChunkMaxLines(),
            config.getInputTokenPricePerMillion(),
            config.getOutputTokenPricePerMillion(),
            strategyReleaseProvider == null
                ? ReviewStrategyRelease.observeDefaults()
                : strategyReleaseProvider.getActiveRelease()
        );
    }
}
