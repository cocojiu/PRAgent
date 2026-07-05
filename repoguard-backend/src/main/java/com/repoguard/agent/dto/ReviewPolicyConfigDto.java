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
    String secretStatus
) {
}
