package com.repoguard.agent.review;

import com.repoguard.agent.config.RabbitReviewQueueProperties;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.mapper.ReviewPolicyConfigMapper;
import com.repoguard.agent.security.SecretCryptoService;
import com.repoguard.agent.tenancy.TenantContext;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ReviewPolicyProvider {

    private final ReviewPolicyConfigMapper reviewPolicyConfigMapper;
    private final SecretCryptoService secretCryptoService;
    private final ReviewStrategyReleaseProvider strategyReleaseProvider;
    private final RabbitReviewQueueProperties reviewQueueProperties;

    @Autowired
    public ReviewPolicyProvider(
        ReviewPolicyConfigMapper reviewPolicyConfigMapper,
        SecretCryptoService secretCryptoService,
        ReviewStrategyReleaseProvider strategyReleaseProvider,
        RabbitReviewQueueProperties reviewQueueProperties
    ) {
        this.reviewPolicyConfigMapper = Objects.requireNonNull(reviewPolicyConfigMapper, "reviewPolicyConfigMapper");
        this.secretCryptoService = Objects.requireNonNull(secretCryptoService, "secretCryptoService");
        this.strategyReleaseProvider = Objects.requireNonNull(strategyReleaseProvider, "strategyReleaseProvider");
        this.reviewQueueProperties = Objects.requireNonNull(reviewQueueProperties, "reviewQueueProperties");
    }

    public ReviewPolicyProvider(
        ReviewPolicyConfigMapper reviewPolicyConfigMapper,
        SecretCryptoService secretCryptoService
    ) {
        this.reviewPolicyConfigMapper = Objects.requireNonNull(reviewPolicyConfigMapper, "reviewPolicyConfigMapper");
        this.secretCryptoService = Objects.requireNonNull(secretCryptoService, "secretCryptoService");
        this.strategyReleaseProvider = null;
        this.reviewQueueProperties = new RabbitReviewQueueProperties();
    }

    ReviewPolicyProvider(
        ReviewPolicyConfigMapper reviewPolicyConfigMapper,
        SecretCryptoService secretCryptoService,
        ReviewStrategyReleaseProvider strategyReleaseProvider
    ) {
        this.reviewPolicyConfigMapper = Objects.requireNonNull(reviewPolicyConfigMapper, "reviewPolicyConfigMapper");
        this.secretCryptoService = Objects.requireNonNull(secretCryptoService, "secretCryptoService");
        this.strategyReleaseProvider = Objects.requireNonNull(strategyReleaseProvider, "strategyReleaseProvider");
        this.reviewQueueProperties = new RabbitReviewQueueProperties();
    }

    public ReviewPolicySettings getSettings() {
        ReviewPolicyConfig config = reviewPolicyConfigMapper.selectByTenantId(
            TenantContext.currentTenantIdOrDefault()
        );
        if (config == null) {
            return ReviewPolicySettings.empty();
        }
        return new ReviewPolicySettings(true, config.getLlmEnabled(),
            new ReviewPolicySettings.Connection(config.getLlmProvider(), config.getModelName(), config.getBaseUrl(),
                secretCryptoService.decrypt(config.getApiKeyValue()), config.getTimeoutSeconds()),
            new ReviewPolicySettings.Generation(config.getTemperature(), config.getMaxTokens()), config.getFallbackToRules(),
            new ReviewPolicySettings.ChunkPolicy(reviewQueueProperties.getWorkerConcurrency(),
                config.getChunkFileThreshold(), config.getChunkLineThreshold(), config.getChunkMaxFiles(), config.getChunkMaxLines()),
            new ReviewPolicySettings.Pricing(config.getInputTokenPricePerMillion(), config.getOutputTokenPricePerMillion(),
                config.getCachedInputTokenPricePerMillion()),
            strategyReleaseProvider == null ? ReviewStrategyRelease.observeDefaults() : strategyReleaseProvider.getActiveRelease());

    }
}
