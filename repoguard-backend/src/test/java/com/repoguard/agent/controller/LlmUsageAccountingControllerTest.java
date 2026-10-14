package com.repoguard.agent.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repoguard.agent.dto.LlmUsageAccountingDto.PendingUsagePage;
import com.repoguard.agent.review.LlmUsageBudgetLedger;
import com.repoguard.agent.review.LlmUsagePendingQuery;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LlmUsageAccountingControllerTest {
    private final LlmUsageBudgetLedger ledger = mock(LlmUsageBudgetLedger.class);
    private final LlmUsagePendingQuery query = mock(LlmUsagePendingQuery.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new LlmUsageAccountingController(ledger, query)).build();

    @Test
    void pageEndpointBindsFiltersAndReturnsTraversalMetadata() throws Exception {
        when(query.page(25, "2026-10", "LEGACY", "cursor"))
            .thenReturn(new PendingUsagePage(List.of(), "next", true));
        mvc.perform(get("/api/v1/config/llm-usage/pending/page")
                .param("limit", "25").param("month", "2026-10").param("status", "LEGACY").param("cursor", "cursor"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items").isArray())
            .andExpect(jsonPath("$.data.hasMore").value(true))
            .andExpect(jsonPath("$.data.nextCursor").value("next"));
    }

    @Test
    void originalEndpointKeepsItsListResponse() throws Exception {
        when(ledger.pendingCalls(50)).thenReturn(List.of());
        mvc.perform(get("/api/v1/config/llm-usage/pending"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
    }
}
