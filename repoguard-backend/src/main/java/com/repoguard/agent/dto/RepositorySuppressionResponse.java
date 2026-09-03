package com.repoguard.agent.dto;

public record RepositorySuppressionResponse(
    Long id,
    String organization,
    String repository,
    String ruleId,
    String fileGlob,
    String symbol,
    String reason,
    String status,
    String operator,
    String expiresAt,
    long previewHitCount,
    long hitCount,
    String createdAt,
    String updatedAt
) {
}
