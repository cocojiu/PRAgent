package com.repoguard.agent.dto;

/**
 * 仪表盘中高亮展示的高风险评审记录。
 */
public record HighRiskReviewDto(
    String title,
    String repository,
    String riskLevel,
    long ruleHits,
    String reviewedAt,
    String status
) {
}
