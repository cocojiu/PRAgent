package com.repoguard.agent.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repoguard.agent.dto.ChartSliceDto;
import com.repoguard.agent.dto.DashboardMetricDto;
import com.repoguard.agent.dto.DashboardOverviewResponse;
import com.repoguard.agent.dto.FailedRuleStatDto;
import com.repoguard.agent.dto.HighRiskReviewDto;
import com.repoguard.agent.dto.ReviewTrendPointDto;
import com.repoguard.agent.dto.SystemHealthItemDto;
import com.repoguard.agent.service.DashboardService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DashboardControllerTest {

    private final DashboardService dashboardService = llmTrendDays -> new DashboardOverviewResponse(
        List.of(new DashboardMetricDto("本周审查", "8", "0.0%", "up", "blue")),
        List.of(new ReviewTrendPointDto("05-31", 1)),
        List.of(new ChartSliceDto("高风险", 1, "#ef4444", "12.5%")),
        List.of(new ChartSliceDto("硬编码密钥检测", 2, "#ef4444", "40.0%")),
        List.of(new HighRiskReviewDto("新增用户导出接口", "spring-boot-demo", "high", 5, "2025-05-31 14:32", "已完成")),
        List.of(new FailedRuleStatDto("硬编码密钥检测", 2, "0.0%", "down", "40.0%")),
        List.of(new SystemHealthItemDto("MySQL", "正常"))
    );

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DashboardController(dashboardService)).build();

    @Test
    void getOverviewReturnsDashboardData() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/overview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.overviewMetrics", hasSize(1)))
            .andExpect(jsonPath("$.data.systemHealth[0].name").value("MySQL"));
    }
}
