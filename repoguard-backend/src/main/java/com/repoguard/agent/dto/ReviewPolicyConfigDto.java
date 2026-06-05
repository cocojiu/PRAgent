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
    String updatedAt
) {
}
