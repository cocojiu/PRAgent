package com.repoguard.agent.dto;

public record UserOperationAuditContext(
    Long operatorId,
    String clientIp,
    String userAgent
) {
}
