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
    List<SystemHealthItemDto> systemHealth,
    List<LlmQualityByModelDto> llmQualityByModel,
    List<LlmQualityByRepositoryDto> llmQualityByRepository,
    List<LlmQualityTrendPointDto> llmQualityTrend
) {
    public DashboardOverviewResponse(
        List<DashboardMetricDto> overviewMetrics,
        List<ReviewTrendPointDto> reviewTrend,
        List<ChartSliceDto> riskDistribution,
        List<ChartSliceDto> ruleHits,
        List<HighRiskReviewDto> highRiskReviews,
        List<FailedRuleStatDto> failedRules,
        List<SystemHealthItemDto> systemHealth
    ) {
        this(
            overviewMetrics,
            reviewTrend,
            riskDistribution,
            ruleHits,
            highRiskReviews,
            failedRules,
            systemHealth,
            List.of(),
            List.of(),
            List.of()
        );
    }
}
