package com.repoguard.agent.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record ReviewPolicyConfigRequest(
    @NotNull Boolean llmEnabled,
    @NotBlank @Size(max = 64) String llmProvider,
    @NotBlank @Size(max = 128) String modelName,
    @Size(max = 512) String baseUrl,
    @Size(max = 4096) String apiKey,
    @NotNull @Min(1) @Max(600) Integer timeoutSeconds,
    @NotNull @DecimalMin("0.00") @DecimalMax("2.00") BigDecimal temperature,
    @NotNull @Min(1) @Max(128000) Integer maxTokens,
    @NotNull Boolean fallbackToRules,
    @NotNull @Min(1) @Max(16) Integer workerConcurrency
) {
}
