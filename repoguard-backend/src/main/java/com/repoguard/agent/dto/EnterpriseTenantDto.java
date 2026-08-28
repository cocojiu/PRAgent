package com.repoguard.agent.dto;

import java.time.LocalDateTime;

public record EnterpriseTenantDto(
    Long tenantId,
    String tenantKey,
    String displayName,
    String status,
    Long statusVersion,
    String statusReason,
    LocalDateTime statusChangedAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
