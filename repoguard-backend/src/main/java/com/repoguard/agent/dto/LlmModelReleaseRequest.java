package com.repoguard.agent.dto;

import jakarta.validation.Valid;
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

/** Aggregate, privacy-safe release evidence; raw prompts and provider responses are never accepted. */
public record LlmModelReleaseRequest(@NotBlank @Size(max = 128) String releaseKey,
    @NotBlank @Size(max = 64) String provider, @NotBlank @Size(max = 128) String modelName,
    @NotBlank @Size(max = 96) String promptVersion, @NotBlank @Size(max = 96) String contextVersion,
    @NotBlank @Size(max = 96) String schemaVersion, @NotBlank @Size(max = 128) String datasetId,
    @NotBlank @Size(max = 64) String datasetVersion, @NotBlank @Pattern(regexp = "(?i)[0-9a-f]{64}") String datasetFingerprint,
    @NotNull @Min(0) @Max(100) Integer trafficPercent, @NotNull Boolean qualityGatePassed,
    @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal precisionRate,
    @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal recallRate,
    @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal anchorRate,
    @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal duplicateRate,
    @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal parseFailureRate,
    @NotNull @Min(0) Long p95LatencyMs, @NotNull @DecimalMin("0.0") BigDecimal averageCost,
    @NotNull @Min(0) Long totalTokens, @Size(max = 10) List<@NotBlank @Size(max = 128) String> blockers,
    @Min(1) Long evaluationReportId) {

    public LlmModelReleaseRequest(String releaseKey, String provider, String modelName, String promptVersion,
        String contextVersion, String schemaVersion, String datasetId, String datasetVersion, String datasetFingerprint,
        Integer trafficPercent, Boolean qualityGatePassed, BigDecimal precisionRate, BigDecimal recallRate,
        BigDecimal anchorRate, BigDecimal duplicateRate, BigDecimal parseFailureRate, Long p95LatencyMs,
        BigDecimal averageCost, Long totalTokens, List<String> blockers) {
        this(releaseKey, provider, modelName, promptVersion, contextVersion, schemaVersion, datasetId, datasetVersion,
            datasetFingerprint, trafficPercent, qualityGatePassed, precisionRate, recallRate, anchorRate, duplicateRate,
            parseFailureRate, p95LatencyMs, averageCost, totalTokens, blockers, null);
    }

    /** Aggregate-only input for a real-PR evaluation; source and provider payloads stay outside this API. */
    public record LlmEvaluationRequest(@NotBlank @Size(max = 128) String datasetId,
        @NotBlank @Size(max = 64) String datasetVersion,
        @NotBlank @Pattern(regexp = "(?i)REAL_PR|OFFLINE_SYNTHETIC") String datasetKind,
        @NotNull @Min(0) @Max(3) Integer sourceRepositoryCount, @NotNull @Min(0) @Max(100) Integer sampleCount,
        @NotNull @Min(0) @Max(100) Integer fixedRegressionSamples, @NotNull @Min(0) @Max(100) Integer rollingObservationSamples,
        @NotNull Boolean authorized, @NotNull Boolean anonymized, @NotNull Boolean humanReviewed,
        @NotBlank @Pattern(regexp = "(?i)[0-9a-f]{64}") String sampleFingerprint,
        @NotBlank @Size(max = 64) String provider, @NotBlank @Size(max = 128) String model,
        @NotBlank @Size(max = 96) String promptVersion, @NotBlank @Size(max = 96) String contextVersion,
        @NotBlank @Size(max = 96) String schemaVersion, @NotBlank @Size(max = 128) String chunkPolicyVersion,
        @NotNull @DecimalMin("0.0") @DecimalMax("2.0") BigDecimal temperature,
        @NotBlank @Size(max = 96) String ruleVersion, @NotBlank @Size(max = 128) String codeRevision,
        @NotNull @Size(min = 1, max = 100) List<@Valid LlmEvaluationObservationRequest> observations,
        @Min(30) @Max(100) Integer minimumSamples) {
    }

    /** One labelled sample represented only by anonymized ids, labels and aggregate counters. */
    public record LlmEvaluationObservationRequest(@NotBlank @Size(max = 128) String caseId,
        @NotBlank @Size(max = 64) String category, boolean expectedFinding, @Size(max = 32) String expectedSeverity,
        boolean predictedFinding, @Size(max = 32) String predictedSeverity, boolean anchorValid,
        @Size(max = 256) String predictionKey, boolean parseSucceeded, @Min(0) long latencyMs,
        @Min(0) long totalTokens, @NotNull @DecimalMin("0.0") BigDecimal estimatedCost, Boolean usefulComment,
        boolean commentPublishAttempted, Boolean commentPublished, Boolean commentFixed, Boolean commentIgnored,
        @Min(0) long ruleFindingCount, @Min(0) long llmFindingCount, @Min(0) long verifiedFindingCount,
        @NotBlank @Pattern(regexp = "(?i)FIXED_REGRESSION|ROLLING_OBSERVATION|UNSPECIFIED") String split,
        @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9._-]{0,63}") String sourceRepositoryKey,
        @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9._-]{0,63}") String language, @Min(0) int changedFileCount,
        @Min(0) int changedLineCount, @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9._-]{0,63}") String fileTypeGroup,
        @Pattern(regexp = "[a-z0-9][a-z0-9._-]{0,127}") String expectedLocationKey) {
    }
}
