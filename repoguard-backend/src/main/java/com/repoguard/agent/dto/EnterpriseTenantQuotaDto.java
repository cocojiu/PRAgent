package com.repoguard.agent.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;

public record EnterpriseTenantQuotaDto(
    Long tenantId,
    String tenantKey,
    Long quotaVersion,
    Integer maxDailyReviews,
    Long monthlyLlmTokenBudget,
    BigDecimal monthlyLlmCostBudget,
    Integer usedReviews,
    LocalDate usageDate,
    LocalDateTime updatedAt
) {

    public EnterpriseTenantQuotaDto(
        Long tenantId,
        String tenantKey,
        Long quotaVersion,
        Integer maxDailyReviews,
        Integer usedReviews,
        LocalDate usageDate,
        LocalDateTime updatedAt
    ) {
        this(
            tenantId,
            tenantKey,
            quotaVersion,
            maxDailyReviews,
            0L,
            BigDecimal.ZERO,
            usedReviews,
            usageDate,
            updatedAt
        );
    }
}
