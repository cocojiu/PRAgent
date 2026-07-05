package com.repoguard.agent.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repoguard.agent.dto.DashboardOverviewResponse;
import com.repoguard.agent.dto.SystemHealthItemDto;
import com.repoguard.agent.service.DashboardService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SystemHealthControllerTest {

    private final DashboardService dashboardService = new DashboardService() {
        @Override
        public DashboardOverviewResponse getOverview(Integer llmTrendDays) {
            return null;
        }

        @Override
        public List<SystemHealthItemDto> getSystemHealth() {
            return List.of(
                new SystemHealthItemDto("MySQL", "normal"),
                new SystemHealthItemDto("RabbitMQ", "normal")
            );
        }
    };
    private final MockMvc mockMvc = MockMvcBuilders
        .standaloneSetup(new SystemHealthController(dashboardService))
        .build();

    @Test
    void getSystemHealthSummaryReturnsStableEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/system/health/summary"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data", hasSize(2)))
            .andExpect(jsonPath("$.data[0].name").value("MySQL"))
            .andExpect(jsonPath("$.data[0].status").value("normal"));
    }
}
