package com.repoguard.agent.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repoguard.agent.dto.LlmStatusDto;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.RabbitMqStatusDto;
import com.repoguard.agent.dto.ReviewQuery;
import com.repoguard.agent.dto.ReviewTaskDetail;
import com.repoguard.agent.dto.ReviewTaskListItem;
import com.repoguard.agent.service.ReviewService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ReviewControllerTest {

    private final ReviewService reviewService = new ReviewService() {
        @Override
        public PageResponse<ReviewTaskListItem> listReviews(ReviewQuery query) {
            ReviewTaskListItem item = new ReviewTaskListItem(
                512L,
                512,
                "新增用户导出接口",
                "spring-boot-demo",
                "repo-guard-demo",
                "a1b2c3d",
                "main",
                "completed",
                "high",
                0,
                "completed",
                "2025-05-31 14:32:21",
                "2 分 48 秒"
            );
            return new PageResponse<>(List.of(item), 1);
        }

        @Override
        public ReviewTaskDetail getReviewDetail(Long id) {
            return new ReviewTaskDetail(
                id,
                512,
                "新增用户导出接口",
                "spring-boot-demo",
                "repo-guard-demo",
                "a1b2c3d",
                "main",
                "completed",
                "high",
                0,
                "completed",
                "2025-05-31 14:32:21",
                "2 分 48 秒",
                "https://github.com/repo-guard-demo/spring-boot-demo/pull/512",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new LlmStatusDto("completed", "2 分 48 秒", "high"),
                new RabbitMqStatusDto(1, 0, "confirmed")
            );
        }
    };

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ReviewController(reviewService)).build();

    @Test
    void listReviewsReturnsPagedItems() throws Exception {
        mockMvc.perform(get("/api/v1/reviews")
                .param("page", "1")
                .param("pageSize", "20")
                .param("status", "completed"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items", hasSize(1)))
            .andExpect(jsonPath("$.data.items[0].status").value("completed"));
    }

    @Test
    void getReviewDetailReturnsTaskDetail() throws Exception {
        mockMvc.perform(get("/api/v1/reviews/512"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(512))
            .andExpect(jsonPath("$.data.prUrl").value("https://github.com/repo-guard-demo/spring-boot-demo/pull/512"))
            .andExpect(jsonPath("$.data.rabbitMq.consumeStatus").value("confirmed"));
    }
}
