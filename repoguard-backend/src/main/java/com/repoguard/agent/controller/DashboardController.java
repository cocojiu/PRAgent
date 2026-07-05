package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.dto.ChartSliceDto;
import com.repoguard.agent.dto.DashboardLlmQualityResponse;
import com.repoguard.agent.dto.DashboardMetricDto;
import com.repoguard.agent.dto.DashboardOverviewResponse;
import com.repoguard.agent.dto.DashboardRulesResponse;
import com.repoguard.agent.dto.HighRiskReviewDto;
import com.repoguard.agent.dto.ReviewTrendPointDto;
import com.repoguard.agent.service.DashboardService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
@ApiRuntimeEnabled
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /**
     * 返回仪表盘概览需要的评审聚合指标。
     */
    @GetMapping("/overview")
    public ApiResponse<DashboardOverviewResponse> getOverview(
        @RequestParam(value = "llmTrendDays", required = false) Integer llmTrendDays
    ) {
        return ApiResponse.ok(dashboardService.getOverview(llmTrendDays));
    }

    @GetMapping("/summary")
    public ApiResponse<List<DashboardMetricDto>> getSummary() {
        return ApiResponse.ok(dashboardService.getSummary());
    }

    @GetMapping("/review-trend")
    public ApiResponse<List<ReviewTrendPointDto>> getReviewTrend() {
        return ApiResponse.ok(dashboardService.getReviewTrend());
    }

    @GetMapping("/risk-distribution")
    public ApiResponse<List<ChartSliceDto>> getRiskDistribution() {
        return ApiResponse.ok(dashboardService.getRiskDistribution());
    }

    @GetMapping("/rules")
    public ApiResponse<DashboardRulesResponse> getRules() {
        return ApiResponse.ok(dashboardService.getRules());
    }

    @GetMapping("/high-risk-reviews")
    public ApiResponse<List<HighRiskReviewDto>> getHighRiskReviews() {
        return ApiResponse.ok(dashboardService.getHighRiskReviews());
    }

    @GetMapping("/llm-quality")
    public ApiResponse<DashboardLlmQualityResponse> getLlmQuality(
        @RequestParam(value = "llmTrendDays", required = false) Integer llmTrendDays
    ) {
        return ApiResponse.ok(dashboardService.getLlmQuality(llmTrendDays));
    }
}
