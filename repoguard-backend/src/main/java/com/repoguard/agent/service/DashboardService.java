package com.repoguard.agent.service;

import com.repoguard.agent.dto.ChartSliceDto;
import com.repoguard.agent.dto.DashboardLlmQualityResponse;
import com.repoguard.agent.dto.DashboardMetricDto;
import com.repoguard.agent.dto.DashboardOverviewResponse;
import com.repoguard.agent.dto.DashboardRulesResponse;
import com.repoguard.agent.dto.HighRiskReviewDto;
import com.repoguard.agent.dto.ReviewTrendPointDto;
import com.repoguard.agent.dto.SystemHealthItemDto;
import java.util.List;

public interface DashboardService {

    /**
     * 基于已持久化的评审任务和问题记录构建仪表盘概览。
     */
    DashboardOverviewResponse getOverview(Integer llmTrendDays);

    default List<DashboardMetricDto> getSummary() {
        return getOverview(null).overviewMetrics();
    }

    default List<ReviewTrendPointDto> getReviewTrend() {
        return getOverview(null).reviewTrend();
    }

    default List<ChartSliceDto> getRiskDistribution() {
        return getOverview(null).riskDistribution();
    }

    default DashboardRulesResponse getRules() {
        DashboardOverviewResponse overview = getOverview(null);
        return new DashboardRulesResponse(overview.ruleHits(), overview.failedRules());
    }

    default List<HighRiskReviewDto> getHighRiskReviews() {
        return getOverview(null).highRiskReviews();
    }

    default DashboardLlmQualityResponse getLlmQuality(Integer llmTrendDays) {
        DashboardOverviewResponse overview = getOverview(llmTrendDays);
        return new DashboardLlmQualityResponse(
            overview.llmQualityByModel(),
            overview.llmQualityByRepository(),
            overview.llmQualityTrend()
        );
    }

    List<SystemHealthItemDto> getSystemHealth();
}
