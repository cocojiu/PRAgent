package com.repoguard.agent.dto;

public record ReviewRuleConfigDto(
    String id,
    String name,
    String scope,
    String severity,
    String status,
    long hitCount,
    String confidence,
    String updatedAt,
    String description
) {
}
