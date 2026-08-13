package com.repoguard.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repoguard.agent.dto.ChartSliceDto;
import com.repoguard.agent.dto.DashboardLlmQualityResponse;
import com.repoguard.agent.dto.DashboardMetricDto;
import com.repoguard.agent.dto.DashboardOverviewResponse;
import com.repoguard.agent.dto.DashboardRulesResponse;
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
            .andExpect(jsonPath("$.data.overviewMetrics[0].label").value("reviews"))
            .andExpect(jsonPath("$.data.overviewMetrics[0].value").value("8"))
            .andExpect(jsonPath("$.data.overviewMetrics[0].trend").value("0.0%"))
            .andExpect(jsonPath("$.data.overviewMetrics[0].trendType").value("up"))
            .andExpect(jsonPath("$.data.overviewMetrics[0].color").value("blue"))
            .andExpect(jsonPath("$.data.reviewTrend[0].date").value("05-31"))
            .andExpect(jsonPath("$.data.riskDistribution[0].value").value(1))
            .andExpect(jsonPath("$.data.ruleHits[0].value").value(2))
            .andExpect(jsonPath("$.data.highRiskReviews[0].repository").value("spring-boot-demo"))
            .andExpect(jsonPath("$.data.failedRules[0].count").value(2))
            .andExpect(jsonPath("$.data.systemHealth[0].name").value("MySQL"))
            .andExpect(jsonPath("$.data.llmQualityByModel[0].taskCount").value(4))
            .andExpect(jsonPath("$.data.llmQualityByRepository[0].taskCount").value(3))
            .andExpect(jsonPath("$.data.llmQualityTrend[0].taskCount").value(5));

        assertThat(dashboardService.lastLlmTrendDays).isEqualTo(30);
    }

    @Test
    void moduleEndpointsReturnStableDashboardContracts() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].value").value("8"))
            .andExpect(jsonPath("$.data[0].trendType").value("up"));

        mockMvc.perform(get("/api/v1/dashboard/review-trend"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].date").value("05-31"))
            .andExpect(jsonPath("$.data[0].value").value(1));

        mockMvc.perform(get("/api/v1/dashboard/risk-distribution"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].value").value(1))
            .andExpect(jsonPath("$.data[0].percent").value("12.5%"));

        mockMvc.perform(get("/api/v1/dashboard/rules"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.ruleHits[0].value").value(2))
            .andExpect(jsonPath("$.data.failedRules[0].count").value(2));

        mockMvc.perform(get("/api/v1/dashboard/high-risk-reviews"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].repository").value("spring-boot-demo"))
            .andExpect(jsonPath("$.data[0].riskLevel").value("high"));

        mockMvc.perform(get("/api/v1/dashboard/llm-quality").param("llmTrendDays", "90"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.byModel[0].taskCount").value(4))
            .andExpect(jsonPath("$.data.byRepository[0].taskCount").value(3))
            .andExpect(jsonPath("$.data.trend[0].taskCount").value(5));

        assertThat(dashboardService.lastLlmQualityTrendDays).isEqualTo(90);
    }

    private static final class RecordingDashboardService implements DashboardService {

        private Integer lastLlmTrendDays;
        private Integer lastLlmQualityTrendDays;

        @Override
        public DashboardOverviewResponse getOverview(Integer llmTrendDays) {
            this.lastLlmTrendDays = llmTrendDays;
            return new DashboardOverviewResponse(
                List.of(new DashboardMetricDto("reviews", "8", "0.0%", "up", "blue")),
                List.of(new ReviewTrendPointDto("05-31", 1)),
                List.of(new ChartSliceDto("high", 1, "#ef4444", "12.5%")),
                List.of(new ChartSliceDto("secret", 2, "#ef4444", "40.0%")),
                List.of(new HighRiskReviewDto("export users", "spring-boot-demo", "high", 5, "2025-05-31 14:32", "done")),
                List.of(new FailedRuleStatDto("secret", 2, "0.0%", "down", "40.0%")),
                List.of(new SystemHealthItemDto("MySQL", "ok")),
                List.of(new LlmQualityByModelDto("openai / gpt-4.1", 4, "2.5s", "1200", "$0.12", "95.0%", "3.0%", "2.0%", "80.0%", "5.0%")),
                List.of(new LlmQualityByRepositoryDto("demo/repo", 3, "4.0%", "1.0%", "75.0%", "6.0%")),
                List.of(new LlmQualityTrendPointDto("2026-06-22", 5, "96.0%", "2.0%", "2.0%"))
            );
        }

        @Override
        public List<DashboardMetricDto> getSummary() {
            return List.of(new DashboardMetricDto("reviews", "8", "0.0%", "up", "blue"));
        }

        @Override
        public List<ReviewTrendPointDto> getReviewTrend() {
            return List.of(new ReviewTrendPointDto("05-31", 1));
        }

        @Override
        public List<ChartSliceDto> getRiskDistribution() {
            return List.of(new ChartSliceDto("high", 1, "#ef4444", "12.5%"));
        }

        @Override
        public DashboardRulesResponse getRules() {
            return new DashboardRulesResponse(
                List.of(new ChartSliceDto("secret", 2, "#ef4444", "40.0%")),
                List.of(new FailedRuleStatDto("secret", 2, "0.0%", "down", "40.0%"))
            );
        }

        @Override
        public List<HighRiskReviewDto> getHighRiskReviews() {
            return List.of(new HighRiskReviewDto("export users", "spring-boot-demo", "high", 5, "2025-05-31 14:32", "done"));
        }

        @Override
        public DashboardLlmQualityResponse getLlmQuality(Integer llmTrendDays) {
            this.lastLlmQualityTrendDays = llmTrendDays;
            return new DashboardLlmQualityResponse(
                List.of(new LlmQualityByModelDto("openai / gpt-4.1", 4, "2.5s", "1200", "$0.12", "95.0%", "3.0%", "2.0%", "80.0%", "5.0%")),
                List.of(new LlmQualityByRepositoryDto("demo/repo", 3, "4.0%", "1.0%", "75.0%", "6.0%")),
                List.of(new LlmQualityTrendPointDto("2026-06-22", 5, "96.0%", "2.0%", "2.0%"))
            );
        }

        @Override
        public List<SystemHealthItemDto> getSystemHealth() {
            return List.of(new SystemHealthItemDto("MySQL", "ok"));
        }
    }
}
