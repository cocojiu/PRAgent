package com.repoguard.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repoguard.agent.dto.ChartSliceDto;
import com.repoguard.agent.dto.DashboardMetricDto;
import com.repoguard.agent.dto.DashboardOverviewResponse;
import com.repoguard.agent.dto.FailedRuleStatDto;
import com.repoguard.agent.dto.HighRiskReviewDto;
import com.repoguard.agent.dto.LlmQualityByModelDto;
import com.repoguard.agent.dto.LlmQualityByRepositoryDto;
import com.repoguard.agent.dto.LlmQualityTrendPointDto;
import com.repoguard.agent.dto.ReviewTrendPointDto;
import com.repoguard.agent.dto.SystemHealthItemDto;
import com.repoguard.agent.service.DashboardService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DashboardControllerTest {

    private final RecordingDashboardService dashboardService = new RecordingDashboardService();
    private final MockMvc mockMvc = MockMvcBuilders
        .standaloneSetup(new DashboardController(dashboardService))
        .build();

    @Test
    void getOverviewKeepsDashboardApiContract() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/overview").param("llmTrendDays", "30"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.message").value("OK"))
            .andExpect(jsonPath("$.timestamp").exists())
            .andExpect(jsonPath("$.data.overviewMetrics", hasSize(1)))
            .andExpect(jsonPath("$.data.overviewMetrics[0].label").value("本周审查"))
            .andExpect(jsonPath("$.data.overviewMetrics[0].value").value("8"))
            .andExpect(jsonPath("$.data.overviewMetrics[0].trend").value("0.0%"))
            .andExpect(jsonPath("$.data.overviewMetrics[0].trendType").value("up"))
            .andExpect(jsonPath("$.data.overviewMetrics[0].color").value("blue"))
            .andExpect(jsonPath("$.data.reviewTrend[0].date").value("05-31"))
            .andExpect(jsonPath("$.data.reviewTrend[0].value").value(1))
            .andExpect(jsonPath("$.data.riskDistribution[0].name").value("高风险"))
            .andExpect(jsonPath("$.data.riskDistribution[0].value").value(1))
            .andExpect(jsonPath("$.data.riskDistribution[0].color").value("#ef4444"))
            .andExpect(jsonPath("$.data.riskDistribution[0].percent").value("12.5%"))
            .andExpect(jsonPath("$.data.ruleHits[0].name").value("硬编码密钥检测"))
            .andExpect(jsonPath("$.data.highRiskReviews[0].title").value("新增用户导出接口"))
            .andExpect(jsonPath("$.data.highRiskReviews[0].repository").value("spring-boot-demo"))
            .andExpect(jsonPath("$.data.highRiskReviews[0].riskLevel").value("high"))
            .andExpect(jsonPath("$.data.highRiskReviews[0].ruleHits").value(5))
            .andExpect(jsonPath("$.data.highRiskReviews[0].reviewedAt").value("2025-05-31 14:32"))
            .andExpect(jsonPath("$.data.highRiskReviews[0].status").value("已完成"))
            .andExpect(jsonPath("$.data.failedRules[0].name").value("硬编码密钥检测"))
            .andExpect(jsonPath("$.data.failedRules[0].count").value(2))
            .andExpect(jsonPath("$.data.failedRules[0].trend").value("0.0%"))
            .andExpect(jsonPath("$.data.failedRules[0].direction").value("down"))
            .andExpect(jsonPath("$.data.failedRules[0].percent").value("40.0%"))
            .andExpect(jsonPath("$.data.systemHealth[0].name").value("MySQL"))
            .andExpect(jsonPath("$.data.systemHealth[0].status").value("正常"))
            .andExpect(jsonPath("$.data.llmQualityByModel[0].model").value("openai / gpt-4.1"))
            .andExpect(jsonPath("$.data.llmQualityByModel[0].taskCount").value(4))
            .andExpect(jsonPath("$.data.llmQualityByModel[0].averageDuration").value("2.5s"))
            .andExpect(jsonPath("$.data.llmQualityByModel[0].averageTokens").value("1200"))
            .andExpect(jsonPath("$.data.llmQualityByModel[0].averageCost").value("$0.12"))
            .andExpect(jsonPath("$.data.llmQualityByModel[0].parseSuccessRate").value("95.0%"))
            .andExpect(jsonPath("$.data.llmQualityByModel[0].fallbackRate").value("3.0%"))
            .andExpect(jsonPath("$.data.llmQualityByModel[0].partialFallbackRate").value("2.0%"))
            .andExpect(jsonPath("$.data.llmQualityByModel[0].validRate").value("80.0%"))
            .andExpect(jsonPath("$.data.llmQualityByModel[0].falsePositiveRate").value("5.0%"))
            .andExpect(jsonPath("$.data.llmQualityByRepository[0].repository").value("demo/repo"))
            .andExpect(jsonPath("$.data.llmQualityByRepository[0].taskCount").value(3))
            .andExpect(jsonPath("$.data.llmQualityByRepository[0].fallbackRate").value("4.0%"))
            .andExpect(jsonPath("$.data.llmQualityByRepository[0].partialFallbackRate").value("1.0%"))
            .andExpect(jsonPath("$.data.llmQualityByRepository[0].validRate").value("75.0%"))
            .andExpect(jsonPath("$.data.llmQualityByRepository[0].falsePositiveRate").value("6.0%"))
            .andExpect(jsonPath("$.data.llmQualityTrend[0].date").value("2026-06-22"))
            .andExpect(jsonPath("$.data.llmQualityTrend[0].taskCount").value(5))
            .andExpect(jsonPath("$.data.llmQualityTrend[0].parseSuccessRate").value("96.0%"))
            .andExpect(jsonPath("$.data.llmQualityTrend[0].fallbackRate").value("2.0%"))
            .andExpect(jsonPath("$.data.llmQualityTrend[0].partialFallbackRate").value("2.0%"));

        assertThat(dashboardService.lastLlmTrendDays).isEqualTo(30);
    }

    private static final class RecordingDashboardService implements DashboardService {

        private Integer lastLlmTrendDays;

        @Override
        public DashboardOverviewResponse getOverview(Integer llmTrendDays) {
            this.lastLlmTrendDays = llmTrendDays;
            return new DashboardOverviewResponse(
                List.of(new DashboardMetricDto("本周审查", "8", "0.0%", "up", "blue")),
                List.of(new ReviewTrendPointDto("05-31", 1)),
                List.of(new ChartSliceDto("高风险", 1, "#ef4444", "12.5%")),
                List.of(new ChartSliceDto("硬编码密钥检测", 2, "#ef4444", "40.0%")),
                List.of(new HighRiskReviewDto("新增用户导出接口", "spring-boot-demo", "high", 5, "2025-05-31 14:32", "已完成")),
                List.of(new FailedRuleStatDto("硬编码密钥检测", 2, "0.0%", "down", "40.0%")),
                List.of(new SystemHealthItemDto("MySQL", "正常")),
                List.of(new LlmQualityByModelDto("openai / gpt-4.1", 4, "2.5s", "1200", "$0.12", "95.0%", "3.0%", "2.0%", "80.0%", "5.0%")),
                List.of(new LlmQualityByRepositoryDto("demo/repo", 3, "4.0%", "1.0%", "75.0%", "6.0%")),
                List.of(new LlmQualityTrendPointDto("2026-06-22", 5, "96.0%", "2.0%", "2.0%"))
            );
        }
    }
}
