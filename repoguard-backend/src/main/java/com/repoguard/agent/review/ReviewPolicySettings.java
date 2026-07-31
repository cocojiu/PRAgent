package com.repoguard.agent.review;

import java.math.BigDecimal;

public record ReviewPolicySettings(
    boolean exists,
    Boolean llmEnabled,
    String llmProvider,
    String modelName,
    String baseUrl,
    String apiKey,
    Integer timeoutSeconds,
    BigDecimal temperature,
    Integer maxTokens,
    Boolean fallbackToRules,
    Integer workerConcurrency,
    Integer chunkFileThreshold,
    Integer chunkLineThreshold,
    Integer chunkMaxFiles,
    Integer chunkMaxLines,
    BigDecimal inputTokenPricePerMillion,
    BigDecimal outputTokenPricePerMillion,
    ReviewStrategyRelease strategyRelease
) {

    public ReviewPolicySettings {
        strategyRelease = strategyRelease == null ? ReviewStrategyRelease.observeDefaults() : strategyRelease;
    }

    public ReviewPolicySettings(
        boolean exists,
        Boolean llmEnabled,
        String llmProvider,
        String modelName,
        String baseUrl,
        String apiKey,
        Integer timeoutSeconds,
        BigDecimal temperature,
        Integer maxTokens,
        Boolean fallbackToRules,
        Integer workerConcurrency,
        Integer chunkFileThreshold,
        Integer chunkLineThreshold,
        Integer chunkMaxFiles,
        Integer chunkMaxLines,
        BigDecimal inputTokenPricePerMillion,
        BigDecimal outputTokenPricePerMillion
    ) {
        this(
            exists,
            llmEnabled,
            llmProvider,
            modelName,
            baseUrl,
            apiKey,
            timeoutSeconds,
            temperature,
            maxTokens,
            fallbackToRules,
            workerConcurrency,
            chunkFileThreshold,
            chunkLineThreshold,
            chunkMaxFiles,
            chunkMaxLines,
            inputTokenPricePerMillion,
            outputTokenPricePerMillion,
            ReviewStrategyRelease.legacyRuntimeDefaults()
        );
    }

    public static ReviewPolicySettings empty() {
        return new ReviewPolicySettings(
            false,
            false,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            ReviewStrategyRelease.observeDefaults()
        );
    }

    public boolean enabled() {
        return Boolean.TRUE.equals(llmEnabled);
    }

    public boolean readyForLlmReview() {
        return hasText(baseUrl)
            && hasText(modelName)
            && hasText(apiKey)
            && !"mock".equalsIgnoreCase(llmProvider);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
