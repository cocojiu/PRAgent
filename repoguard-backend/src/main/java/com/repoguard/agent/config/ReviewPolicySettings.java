package com.repoguard.agent.config;

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
    Integer workerConcurrency
) {

    public static ReviewPolicySettings empty() {
        return new ReviewPolicySettings(false, false, null, null, null, null, null, null, null, null, null);
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
