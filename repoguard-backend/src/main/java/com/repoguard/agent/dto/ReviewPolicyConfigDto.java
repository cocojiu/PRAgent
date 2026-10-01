package com.repoguard.agent.dto;

import java.math.BigDecimal;

public record ReviewPolicyConfigDto(
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
    String updatedAt,
    String secretStatus,
    BigDecimal cachedInputTokenPricePerMillion
) {
    public ReviewPolicyConfigDto(Boolean llmEnabled, String llmProvider, String modelName, String baseUrl,
        String apiKey, Integer timeoutSeconds, BigDecimal temperature, Integer maxTokens, Boolean fallbackToRules,
        Integer workerConcurrency, Integer chunkFileThreshold, Integer chunkLineThreshold, Integer chunkMaxFiles,
        Integer chunkMaxLines, BigDecimal inputTokenPricePerMillion, BigDecimal outputTokenPricePerMillion,
        String updatedAt, String secretStatus) {
        this(llmEnabled, llmProvider, modelName, baseUrl, apiKey, timeoutSeconds, temperature, maxTokens,
            fallbackToRules, workerConcurrency, chunkFileThreshold, chunkLineThreshold, chunkMaxFiles, chunkMaxLines,
            inputTokenPricePerMillion, outputTokenPricePerMillion, updatedAt, secretStatus, null);
    }
}
