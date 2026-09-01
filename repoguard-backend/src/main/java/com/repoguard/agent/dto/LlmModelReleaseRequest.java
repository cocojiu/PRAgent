package com.repoguard.agent.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

/**
 * Aggregate, privacy-safe evidence used to register or promote a model release.
 * Raw prompts and provider responses are intentionally not accepted by the API.
 */
public record LlmModelReleaseRequest(
    @NotBlank @Size(max = 128) String releaseKey,
    @NotBlank @Size(max = 64) String provider,
    @NotBlank @Size(max = 128) String modelName,
    @NotBlank @Size(max = 96) String promptVersion,
    @NotBlank @Size(max = 96) String contextVersion,
    @NotBlank @Size(max = 96) String schemaVersion,
    @NotBlank @Size(max = 128) String datasetId,
    @NotBlank @Size(max = 64) String datasetVersion,
    @NotBlank @Pattern(regexp = "(?i)[0-9a-f]{64}") String datasetFingerprint,
    @NotNull @Min(0) @Max(100) Integer trafficPercent,
    @NotNull Boolean qualityGatePassed,
    @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal precisionRate,
    @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal recallRate,
    @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal anchorRate,
    @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal duplicateRate,
    @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal parseFailureRate,
    @NotNull @Min(0) Long p95LatencyMs,
    @NotNull @DecimalMin("0.0") BigDecimal averageCost,
    @NotNull @Min(0) Long totalTokens,
    @Size(max = 10) List<@NotBlank @Size(max = 128) String> blockers
) {
}
