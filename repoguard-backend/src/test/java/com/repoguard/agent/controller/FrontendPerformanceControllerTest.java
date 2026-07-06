package com.repoguard.agent.controller;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repoguard.agent.service.FrontendPerformanceObservationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class FrontendPerformanceControllerTest {

    private final FrontendPerformanceObservationService service = mock(FrontendPerformanceObservationService.class);
    private final MockMvc mockMvc = MockMvcBuilders
        .standaloneSetup(new FrontendPerformanceController(service))
        .build();

    @Test
    void recordPerformanceAcceptsFrontendObservationBatch() throws Exception {
        mockMvc.perform(post("/api/v1/observability/frontend/performance")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "route": "overview",
                      "apiRequests": [
                        {
                          "operation": "fetchDashboardSummary",
                          "path": "/api/v1/dashboard/summary",
                          "method": "GET",
                          "status": 200,
                          "result": "success",
                          "startedAtMs": 12,
                          "durationMs": 48
                        }
                      ],
                      "longTasks": [
                        { "startedAtMs": 90, "durationMs": 83 }
                      ]
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value("OK"));

        verify(service).record(argThat(request ->
            "overview".equals(request.route())
                && request.apiRequests().size() == 1
                && request.longTasks().size() == 1
        ));
    }
}
