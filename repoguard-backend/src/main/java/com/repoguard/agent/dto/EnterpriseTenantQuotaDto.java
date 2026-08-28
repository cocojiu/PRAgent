package com.repoguard.agent.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record EnterpriseTenantQuotaDto(
    Long tenantId,
    String tenantKey,
    Long quotaVersion,
    Integer maxDailyReviews,
    Integer usedReviews,
    LocalDate usageDate,
    LocalDateTime updatedAt
) {
}
