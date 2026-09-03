package com.repoguard.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import java.math.BigDecimal;

public record EnterpriseTenantQuotaRequest(
    @NotNull @Min(1) Long expectedVersion,
    @NotNull @Min(1) @Max(1_000_000) Integer maxDailyReviews,
    @NotNull @Min(0) @Max(100_000_000_000L) Long monthlyLlmTokenBudget,
    @NotNull @DecimalMin("0.0") @DecimalMax("1000000000.0") BigDecimal monthlyLlmCostBudget
) {

    public EnterpriseTenantQuotaRequest(Long expectedVersion, Integer maxDailyReviews) {
        this(expectedVersion, maxDailyReviews, 0L, BigDecimal.ZERO);
    }
}
