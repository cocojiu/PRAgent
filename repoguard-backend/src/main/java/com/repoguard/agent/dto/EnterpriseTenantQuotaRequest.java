package com.repoguard.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record EnterpriseTenantQuotaRequest(
    @NotNull @Min(1) Long expectedVersion,
    @NotNull @Min(1) @Max(1_000_000) Integer maxDailyReviews
) {
}
