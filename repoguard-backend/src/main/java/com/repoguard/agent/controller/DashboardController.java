package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.dto.DashboardOverviewResponse;
import com.repoguard.agent.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
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
}
