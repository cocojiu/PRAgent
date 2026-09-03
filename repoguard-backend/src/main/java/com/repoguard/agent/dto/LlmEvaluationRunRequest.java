package com.repoguard.agent.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Starts a privacy-safe evaluation from a dataset directory outside the repository.
 * The request deliberately carries no source, patch, prompt or provider payload.
 */
public record LlmEvaluationRunRequest(
    @NotBlank @Size(max = 128) String runKey,
    @NotBlank @Size(max = 512) String dataDirectory,
    @NotNull @Min(1) @Max(8) Integer maxConcurrency,
    @NotNull @Min(1) @Max(1_000_000) Long maxTokens,
    @NotNull @DecimalMin("0.0") BigDecimal maxCost,
    @NotNull @Min(1) @Max(3_600) Integer maxDurationSeconds
) {
}
