package com.repoguard.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repoguard.agent.dto.FindingFeedbackRequest;
import com.repoguard.agent.dto.FindingFeedbackResponse;
import com.repoguard.agent.dto.GithubCommentPreviewItem;
import com.repoguard.agent.dto.GithubCommentPreviewResponse;
import com.repoguard.agent.dto.GithubCommentPublicationBatchDto;
import com.repoguard.agent.dto.GithubCommentPublicationHistoryItem;
import com.repoguard.agent.dto.GithubCommentPublicationHistoryResponse;
import com.repoguard.agent.dto.GithubCommentPublishItem;
import com.repoguard.agent.dto.GithubCommentPublishResponse;
import com.repoguard.agent.dto.GithubCommentWritebackCheck;
import com.repoguard.agent.dto.GithubPullRequestOption;
import com.repoguard.agent.dto.GithubPullRequestOptionsResponse;
import com.repoguard.agent.dto.HumanReviewRequest;
import com.repoguard.agent.dto.HumanReviewResponse;
import com.repoguard.agent.dto.LlmStatusDto;
import com.repoguard.agent.dto.ManualReviewRequest;
import com.repoguard.agent.dto.ManualReviewResponse;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.RabbitMqStatusDto;
import com.repoguard.agent.dto.ReviewQuery;
import com.repoguard.agent.dto.ReviewRetryResponse;
import com.repoguard.agent.dto.ReviewTaskDetail;
import com.repoguard.agent.dto.ReviewTaskListItem;
import com.repoguard.agent.dto.ReviewTaskStatusResponse;
import com.repoguard.agent.dto.ReviewTimelineItem;
import com.repoguard.agent.security.AuthTokenFilter;
import com.repoguard.agent.security.AuthTokenService;
import com.repoguard.agent.service.ReviewService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ReviewControllerTest {

    private ReviewQuery lastListQuery;

    private final ReviewService reviewService = new ReviewService() {
        @Override
        public PageResponse<ReviewTaskListItem> listReviews(ReviewQuery query) {
            lastListQuery = query;
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
                "github_pr_picker",
                "github_pr_picker",
                "2025-05-31 14:32:21",
                "2 分 48 秒",
                null,
                null,
                null
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
                "github_pr_picker",
                "github_pr_picker",
                "2025-05-31 14:32:21",
                "2 分 48 秒",
                null,
                null,
                null,
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
        public ReviewTaskStatusResponse getReviewStatus(Long id) {
            return new ReviewTaskStatusResponse(
                id,
                "reviewing",
                "medium",
                "reviewing",
                "72s",
                "2026-06-12 10:20:30",
                null,
                null,
                null,
                new ReviewTimelineItem("GitHub diff fetched", "10:20:30", "current")
            );
        }

        @Override
        public GithubCommentPreviewResponse getGithubCommentPreview(Long id) {
            return new GithubCommentPreviewResponse(
                id,
                512,
                "https://github.com/repo-guard-demo/spring-boot-demo/pull/512",
                new GithubCommentWritebackCheck(
                    "ready",
                    "success",
                    "repo-guard-demo",
                    "spring-boot-demo",
                    "repo-guard-demo",
                    "spring-boot-demo",
                    true,
                    true,
                    true,
                    null,
                    List.of("GitHub 回写配置与当前任务仓库匹配。")
                ),
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
                    "line",
                    true,
                    "published",
                    "GitHub comment published",
                    null,
                    null,
                    null,
                    "https://github.com/repo/pull/1#discussion_r1",
                    1001L,
                    "2026-06-07 10:00:00"
                ))
            );
        }

        @Override
        public GithubCommentPublicationHistoryResponse getGithubCommentPublicationHistory(Long id, int page, int pageSize, String status) {
            return new GithubCommentPublicationHistoryResponse(
                id,
                1,
                page,
                pageSize,
                status,
                List.of(new GithubCommentPublicationBatchDto(
                    10L,
                    "completed",
                    1,
                    1,
                    1,
                    0,
                    0,
                    "2026-06-09 12:00:00",
                    "2026-06-09 12:00:01",
                    List.of(new GithubCommentPublicationHistoryItem(
                        1L,
                        "src/App.java",
                        12,
                        "line",
                        true,
                        "published",
                        "GitHub comment published",
                        null,
                        null,
                        null,
                        "https://github.com/repo/pull/1#discussion_r1",
                        1001L,
                        "2026-06-09 12:00:01"
                    ))
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
                    "a1b2c3d",
                    "octocat",
                    "https://github.com/repo-guard-demo/spring-boot-demo/pull/512",
                    "2026-06-07T08:00:00Z"
                ))
            );
        }

        @Override
        public ManualReviewResponse triggerManualReview(ManualReviewRequest request) {
            return new ManualReviewResponse(9001L, "queued", "Review task queued", false, "github_pr_picker", "github_pr_picker");
        }

        @Override
        public HumanReviewResponse submitHumanReview(Long id, HumanReviewRequest request, String operator) {
            return new HumanReviewResponse(
                id,
                "changes_requested",
                true,
                "changes_requested",
                request.note(),
                operator,
                "2026-06-13 13:45:00",
                "Human review requested changes"
            );
        }

        @Override
        public FindingFeedbackResponse updateFindingFeedback(Long id, Long findingId, FindingFeedbackRequest request, String operator) {
            return new FindingFeedbackResponse(
                findingId,
                id,
                request.status(),
                request.note(),
                operator,
                "2026-06-13 14:20:00"
            );
        }

        @Override
        public ReviewRetryResponse retryReview(Long id) {
            return new ReviewRetryResponse(id, "queued", "Review task queued for retry", 2);
        }
    };

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ReviewController(reviewService)).build();

    @Test
    void listReviewsReturnsPagedItems() throws Exception {
        mockMvc.perform(get("/api/v1/reviews")
                .param("page", "1")
                .param("pageSize", "20")
                .param("status", "completed")
                .param("source", "github_pr_picker")
                .param("triggerSource", "existing_reused"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items", hasSize(1)))
            .andExpect(jsonPath("$.data.items[0].status").value("completed"))
            .andExpect(jsonPath("$.data.items[0].source").value("github_pr_picker"))
            .andExpect(jsonPath("$.data.items[0].triggerSource").value("github_pr_picker"));
        assertThat(lastListQuery.source()).isEqualTo("github_pr_picker");
        assertThat(lastListQuery.triggerSource()).isEqualTo("existing_reused");
    }

    @Test
    void getReviewDetailReturnsTaskDetail() throws Exception {
        mockMvc.perform(get("/api/v1/reviews/512"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(512))
            .andExpect(jsonPath("$.data.source").value("github_pr_picker"))
            .andExpect(jsonPath("$.data.triggerSource").value("github_pr_picker"))
            .andExpect(jsonPath("$.data.prUrl").value("https://github.com/repo-guard-demo/spring-boot-demo/pull/512"))
            .andExpect(jsonPath("$.data.rabbitMq.consumeStatus").value("confirmed"));
    }

    @Test
    void getReviewStatusReturnsLightweightSnapshot() throws Exception {
        mockMvc.perform(get("/api/v1/reviews/512/status"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(512))
            .andExpect(jsonPath("$.data.status").value("reviewing"))
            .andExpect(jsonPath("$.data.llmStatus").value("reviewing"))
            .andExpect(jsonPath("$.data.updatedAt").value("2026-06-12 10:20:30"))
            .andExpect(jsonPath("$.data.latestTimeline.label").value("GitHub diff fetched"))
            .andExpect(jsonPath("$.data.latestTimeline.status").value("current"));
    }

    @Test
    void getGithubCommentPreviewReturnsCommentDrafts() throws Exception {
        mockMvc.perform(get("/api/v1/reviews/512/github-comments/preview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.commentableCount").value(1))
            .andExpect(jsonPath("$.data.writebackCheck.status").value("ready"))
            .andExpect(jsonPath("$.data.writebackCheck.repositoryMatched").value(true))
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
    void getGithubCommentPublicationHistoryReturnsBatches() throws Exception {
        mockMvc.perform(get("/api/v1/reviews/512/github-comments/publications"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.batches", hasSize(1)))
            .andExpect(jsonPath("$.data.batches[0].batchId").value(10))
            .andExpect(jsonPath("$.data.batches[0].items[0].status").value("published"));
    }

    @Test
    void listConfiguredGithubPullRequestsReturnsOptions() throws Exception {
        mockMvc.perform(get("/api/v1/reviews/github/pull-requests"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.organization").value("repo-guard-demo"))
            .andExpect(jsonPath("$.data.repository").value("spring-boot-demo"))
            .andExpect(jsonPath("$.data.items[0].number").value(512))
            .andExpect(jsonPath("$.data.items[0].headSha").value("a1b2c3d"));
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
                      "branch": "main",
                      "source": "github_pr_picker"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.taskId").value(9001))
            .andExpect(jsonPath("$.data.status").value("queued"))
            .andExpect(jsonPath("$.data.existing").value(false))
            .andExpect(jsonPath("$.data.source").value("github_pr_picker"))
            .andExpect(jsonPath("$.data.triggerSource").value("github_pr_picker"));
    }

    @Test
    void retryReviewReturnsQueuedTask() throws Exception {
        mockMvc.perform(post("/api/v1/reviews/512/retry"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.taskId").value(512))
            .andExpect(jsonPath("$.data.status").value("queued"))
            .andExpect(jsonPath("$.data.retryCount").value(2));
    }

    @Test
    void submitHumanReviewReturnsDecision() throws Exception {
        mockMvc.perform(post("/api/v1/reviews/512/human-review")
                .requestAttr(AuthTokenFilter.AUTHENTICATED_USER_ATTRIBUTE, authenticatedUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "action": "changes_requested",
                      "note": "请先修复高风险 finding"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.taskId").value(512))
            .andExpect(jsonPath("$.data.status").value("changes_requested"))
            .andExpect(jsonPath("$.data.humanReviewStatus").value("changes_requested"))
            .andExpect(jsonPath("$.data.humanReviewNote").value("请先修复高风险 finding"))
            .andExpect(jsonPath("$.data.humanReviewBy").value("review-lead"));
    }

    @Test
    void updateFindingFeedbackReturnsDecision() throws Exception {
        mockMvc.perform(post("/api/v1/reviews/512/findings/1/feedback")
                .requestAttr(AuthTokenFilter.AUTHENTICATED_USER_ATTRIBUTE, authenticatedUser())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "status": "false_positive",
                      "note": "Confirmed by owner"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.taskId").value(512))
            .andExpect(jsonPath("$.data.findingId").value(1))
            .andExpect(jsonPath("$.data.feedbackStatus").value("false_positive"))
            .andExpect(jsonPath("$.data.feedbackNote").value("Confirmed by owner"))
            .andExpect(jsonPath("$.data.feedbackBy").value("review-lead"));
    }

    private AuthTokenService.AuthenticatedUser authenticatedUser() {
        return new AuthTokenService.AuthenticatedUser(1001L, "review-lead", "ADMIN", 9999999999L);
    }
}
