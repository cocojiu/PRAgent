package com.repoguard.agent.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repoguard.agent.dto.GithubCommentPreviewItem;
import com.repoguard.agent.dto.GithubCommentPreviewResponse;
import com.repoguard.agent.dto.GithubCommentPublishItem;
import com.repoguard.agent.dto.GithubCommentPublishResponse;
import com.repoguard.agent.dto.GithubPullRequestOption;
import com.repoguard.agent.dto.GithubPullRequestOptionsResponse;
import com.repoguard.agent.dto.LlmStatusDto;
import com.repoguard.agent.dto.ManualReviewRequest;
import com.repoguard.agent.dto.ManualReviewResponse;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.RabbitMqStatusDto;
import com.repoguard.agent.dto.ReviewQuery;
import com.repoguard.agent.dto.ReviewTaskDetail;
import com.repoguard.agent.dto.ReviewTaskListItem;
import com.repoguard.agent.service.ReviewService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
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

        @Override
        public GithubCommentPreviewResponse getGithubCommentPreview(Long id) {
            return new GithubCommentPreviewResponse(
                id,
                512,
                "https://github.com/repo-guard-demo/spring-boot-demo/pull/512",
                1,
                1,
                0,
                List.of(new GithubCommentPreviewItem(
                    1L,
                    "low",
                    "src/App.java",
                    12,
                    "Use logger",
                    "Replace stdout with logger",
                    "**RepoGuard LOW finding**\n\nUse logger\n\n**建议**：Replace stdout with logger",
                    true,
                    "line",
                    null,
                    false,
                    null,
                    null,
                    null,
                    null
                ))
            );
        }

        @Override
        public GithubCommentPublishResponse publishGithubComments(Long id) {
            return new GithubCommentPublishResponse(
                id,
                1,
                1,
                1,
                0,
                0,
                List.of(new GithubCommentPublishItem(
                    1L,
                    "src/App.java",
                    12,
                    true,
                    "published",
                    "GitHub comment published",
                    "https://github.com/repo/pull/1#discussion_r1",
                    1001L,
                    "2026-06-07 10:00:00"
                ))
            );
        }

        @Override
        public GithubPullRequestOptionsResponse listConfiguredGithubPullRequests() {
            return new GithubPullRequestOptionsResponse(
                "repo-guard-demo",
                "spring-boot-demo",
                List.of(new GithubPullRequestOption(
                    512,
                    "Manual review smoke test",
                    "main",
                    "a1b2c3d",
                    "octocat",
                    "https://github.com/repo-guard-demo/spring-boot-demo/pull/512",
                    "2026-06-07T08:00:00Z"
                ))
            );
        }

        @Override
        public ManualReviewResponse triggerManualReview(ManualReviewRequest request) {
            return new ManualReviewResponse(9001L, "queued", "Review task queued", false);
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

    @Test
    void getGithubCommentPreviewReturnsCommentDrafts() throws Exception {
        mockMvc.perform(get("/api/v1/reviews/512/github-comments/preview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.commentableCount").value(1))
            .andExpect(jsonPath("$.data.items", hasSize(1)))
            .andExpect(jsonPath("$.data.items[0].file").value("src/App.java"))
            .andExpect(jsonPath("$.data.items[0].commentable").value(true));
    }

    @Test
    void publishGithubCommentsReturnsPublishSummary() throws Exception {
        mockMvc.perform(post("/api/v1/reviews/512/github-comments"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.attemptedCount").value(1))
            .andExpect(jsonPath("$.data.succeededCount").value(1))
            .andExpect(jsonPath("$.data.items[0].status").value("published"));
    }

    @Test
    void listConfiguredGithubPullRequestsReturnsOptions() throws Exception {
        mockMvc.perform(get("/api/v1/reviews/github/pull-requests"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.organization").value("repo-guard-demo"))
            .andExpect(jsonPath("$.data.repository").value("spring-boot-demo"))
            .andExpect(jsonPath("$.data.items[0].number").value(512));
    }

    @Test
    void triggerManualReviewReturnsQueuedTask() throws Exception {
        mockMvc.perform(post("/api/v1/reviews/manual")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "organization": "repo-guard-demo",
                      "repository": "spring-boot-demo",
                      "prNumber": 512,
                      "title": "Manual review smoke test",
                      "commit": "a1b2c3d",
                      "branch": "main"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.taskId").value(9001))
            .andExpect(jsonPath("$.data.status").value("queued"))
            .andExpect(jsonPath("$.data.existing").value(false));
    }
}
