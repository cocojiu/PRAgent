package com.repoguard.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ReviewPolicySettingsRequest(
    @NotNull @Min(100) @Max(5000) Integer maxDiffLines,
    @NotNull @Min(10) @Max(300) Integer llmTimeoutSeconds,
    @NotNull @Min(1) @Max(10) Integer workerConcurrency,
    @NotNull Boolean autoComment,
    @NotNull Boolean autoRetry
) {
}
