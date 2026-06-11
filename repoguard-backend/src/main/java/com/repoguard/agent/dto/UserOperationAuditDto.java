package com.repoguard.agent.dto;

import java.time.LocalDateTime;

public record UserOperationAuditDto(
    Long id,
    Long operatorUserId,
    String operatorUsername,
    Long targetUserId,
    String targetUsername,
    String action,
    String beforeValue,
    String afterValue,
    String clientIp,
    String userAgent,
    LocalDateTime createdAt
) {
}
