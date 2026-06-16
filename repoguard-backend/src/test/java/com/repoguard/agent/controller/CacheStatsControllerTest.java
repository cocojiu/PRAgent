package com.repoguard.agent.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repoguard.agent.dto.CacheStatsItemDto;
import com.repoguard.agent.dto.CacheStatsResponse;
import com.repoguard.agent.service.CacheStatsService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CacheStatsControllerTest {

    private final CacheStatsService service = () -> new CacheStatsResponse(List.of(
        new CacheStatsItemDto("dashboardOverview", 1, 3, 2, 1, 0.6666666667d, 0)
    ));

    private final MockMvc mockMvc = MockMvcBuilders
        .standaloneSetup(new CacheStatsController(service))
        .build();

    @Test
    void getStatsReturnsCacheStats() throws Exception {
        mockMvc.perform(get("/api/v1/cache/stats"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.caches", hasSize(1)))
            .andExpect(jsonPath("$.data.caches[0].name").value("dashboardOverview"))
            .andExpect(jsonPath("$.data.caches[0].estimatedSize").value(1))
            .andExpect(jsonPath("$.data.caches[0].requestCount").value(3))
            .andExpect(jsonPath("$.data.caches[0].hitCount").value(2))
            .andExpect(jsonPath("$.data.caches[0].missCount").value(1));
    }
}
