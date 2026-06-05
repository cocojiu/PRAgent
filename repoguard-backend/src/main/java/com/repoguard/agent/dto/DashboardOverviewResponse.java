package com.repoguard.agent.dto;

import java.util.List;

/**
 * 仪表盘概览响应，包含指标、图表序列、风险列表和系统健康状态。
 */
public record DashboardOverviewResponse(
    List<DashboardMetricDto> overviewMetrics,
    List<ReviewTrendPointDto> reviewTrend,
    List<ChartSliceDto> riskDistribution,
    List<ChartSliceDto> ruleHits,
    List<HighRiskReviewDto> highRiskReviews,
    List<FailedRuleStatDto> failedRules,
    List<SystemHealthItemDto> systemHealth
) {
}
