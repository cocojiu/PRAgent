package com.repoguard.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record SecuritySettingsRequest(
    @NotNull Boolean webhookSignature,
    @NotNull Boolean secretMasking,
    @NotNull Boolean publicRepoAllowed,
    @NotNull @Min(1) @Max(180) Integer tokenTtlDays
) {
}
