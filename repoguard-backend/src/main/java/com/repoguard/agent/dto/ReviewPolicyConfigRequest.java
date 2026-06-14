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
    @NotNull @Min(1) @Max(16) Integer workerConcurrency,
    @NotNull @Min(1) @Max(200) Integer chunkFileThreshold,
    @NotNull @Min(1) @Max(50000) Integer chunkLineThreshold,
    @NotNull @Min(1) @Max(50) Integer chunkMaxFiles,
    @NotNull @Min(1) @Max(50000) Integer chunkMaxLines,
    @NotNull @DecimalMin("0.0000") @DecimalMax("9999.0000") BigDecimal inputTokenPricePerMillion,
    @NotNull @DecimalMin("0.0000") @DecimalMax("9999.0000") BigDecimal outputTokenPricePerMillion
) {
}
