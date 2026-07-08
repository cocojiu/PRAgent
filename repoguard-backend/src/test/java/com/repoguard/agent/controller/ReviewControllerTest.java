package com.repoguard.agent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.repoguard.agent.common.GlobalExceptionHandler;
import com.repoguard.agent.dto.FindingFeedbackRequest;
import com.repoguard.agent.dto.FindingFeedbackResponse;
import com.repoguard.agent.dto.FindingSeverityCountsDto;
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
import com.repoguard.agent.dto.ChangedFileDto;
import com.repoguard.agent.dto.ChunkedReviewDto;
import com.repoguard.agent.dto.MissingTestDto;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.PrReviewSummaryDto;
import com.repoguard.agent.dto.PrRiskFileDto;
import com.repoguard.agent.dto.PrRiskProfileDto;
import com.repoguard.agent.dto.RabbitMqStatusDto;
import com.repoguard.agent.dto.ReviewQuery;
import com.repoguard.agent.dto.ReviewFindingDto;
import com.repoguard.agent.dto.ReviewRetryResponse;
import com.repoguard.agent.dto.ReviewTaskListItem;
import com.repoguard.agent.dto.ReviewTaskStatusResponse;
import com.repoguard.agent.dto.ReviewTaskSummary;
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
    private Long lastFindingsTaskId;
    private int lastFindingsPage;
    private int lastFindingsPageSize;
    private String lastFindingsSeverity;
    private String lastFindingsCategory;
    private String lastFindingsFeedbackStatus;
    private Long lastChangedFilesTaskId;
    private int lastChangedFilesPage;
    private int lastChangedFilesPageSize;
    private Boolean lastChangedFilesHasFinding;
    private Long lastMissingTestsTaskId;
    private int lastMissingTestsPage;
    private int lastMissingTestsPageSize;
    private int lastPublicationPage;
    private int lastPublicationPageSize;
    private String lastPublicationStatus;
    private Long lastPreviewTaskId;
    private int lastPreviewPage;
    private int lastPreviewPageSize;
    private boolean lastPreviewCommentableOnly;

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
                null,
                true,
                "pending",
                "Need owner confirmation",
                "review-lead",
                "2026-06-12 11:00:00"
            );
            return new PageResponse<>(List.of(item), 1);
        }

        @Override
        public List<String> listRepositories() {
            return List.of("api", "spring-boot-demo", "web");
        }

        @Override
        public ReviewTaskSummary getReviewDetail(Long id) {
            return new ReviewTaskSummary(
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
                "github_error",
                "GitHub API rate limited",
                "Retry after rate limit resets",
                "https://github.com/repo-guard-demo/spring-boot-demo/pull/512",
                List.of(new ReviewFindingDto(
                    1L,
                    "high",
                    "src/App.java",
                    12,
                    "Use logger",
                    "Replace stdout with logger",
                    "HIGH",
                    "System.out.println(password)",
                    "Secret may leak to stdout",
                    "log.info(\"user exported\")",
                    true,
                    "security",
                    "valid",
                    "Confirmed by owner",
                    "review-lead",
                    "2026-06-12 12:00:00"
                )),
                List.of(new MissingTestDto("UserExportControllerTest", "exportUsers", "controller", "Add authorization regression test")),
                List.of(new ChangedFileDto("src/App.java", "modified", 12, 3)),
                List.of(new ReviewTimelineItem("GitHub diff fetched", "10:20:30", "done")),
                new PrRiskProfileDto(
                    91,
                    "critical",
                    "High risk export endpoint change.",
                    true,
                    "Touches user export and logging.",
                    List.of("auth-sensitive", "secret-risk"),
                    List.of(new PrRiskFileDto("src/App.java", "modified", 12, 3, 2, 91, List.of("secret-risk")))
                ),
                new PrReviewSummaryDto(
                    "critical",
                    "Manual review required before merge.",
                    "block",
                    false,
                    true,
                    List.of("Secret logging risk"),
                    List.of("src/App.java"),
                    "RepoGuard summary body"
                ),
                new LlmStatusDto("completed", "2 分 48 秒", "high"),
                new ChunkedReviewDto(true, 3, "high", 2, 0, List.of("large_pr")),
                new RabbitMqStatusDto(1, 0, "confirmed"),
                true,
                "pending",
                "Need owner confirmation",
                "review-lead",
                "2026-06-12 11:00:00",
                1L,
                1L,
                1L,
                new FindingSeverityCountsDto(1L, 2L, 3L, 4L, 5L)
            );
        }

        @Override
        public PageResponse<ReviewFindingDto> listReviewFindings(
            Long id,
            int page,
            int pageSize,
            String severity,
            String category,
            String feedbackStatus
        ) {
            lastFindingsTaskId = id;
            lastFindingsPage = page;
            lastFindingsPageSize = pageSize;
            lastFindingsSeverity = severity;
            lastFindingsCategory = category;
            lastFindingsFeedbackStatus = feedbackStatus;
            return new PageResponse<>(List.of(new ReviewFindingDto(
                1L,
                "high",
                "src/App.java",
                12,
                "Use logger",
                "Replace stdout with logger",
                "HIGH",
                "System.out.println(password)",
                "Secret may leak to stdout",
                "log.info(\"user exported\")",
                true,
                "security",
                "valid",
                "Confirmed by owner",
                "review-lead",
                "2026-06-12 12:00:00"
            )), 1);
        }

        @Override
        public PageResponse<ChangedFileDto> listChangedFiles(Long id, int page, int pageSize, Boolean hasFinding) {
            lastChangedFilesTaskId = id;
            lastChangedFilesPage = page;
            lastChangedFilesPageSize = pageSize;
            lastChangedFilesHasFinding = hasFinding;
            return new PageResponse<>(List.of(new ChangedFileDto("src/App.java", "modified", 12, 3)), 1);
        }

        @Override
        public PageResponse<MissingTestDto> listMissingTests(Long id, int page, int pageSize) {
            lastMissingTestsTaskId = id;
            lastMissingTestsPage = page;
            lastMissingTestsPageSize = pageSize;
            return new PageResponse<>(
                List.of(new MissingTestDto("UserExportControllerTest", "exportUsers", "controller", "Add authorization regression test")),
                1
            );
        }

        @Override
        public List<ReviewTimelineItem> listReviewTimeline(Long id, int limit) {
            return List.of(
                new ReviewTimelineItem("Review running", "10:20:00", "current"),
                new ReviewTimelineItem("Review completed", "10:21:00", "done")
            ).stream().limit(limit).toList();
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
                new ReviewTimelineItem("GitHub diff fetched", "10:20:30", "current"),
                true,
                "pending",
                "Need owner confirmation",
                "review-lead",
                "2026-06-12 11:00:00"
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
        public GithubCommentPreviewResponse getGithubCommentPreview(
            Long id,
            int page,
            int pageSize,
            boolean commentableOnly
        ) {
            lastPreviewTaskId = id;
            lastPreviewPage = page;
            lastPreviewPageSize = pageSize;
            lastPreviewCommentableOnly = commentableOnly;
            GithubCommentPreviewResponse preview = getGithubCommentPreview(id);
            return new GithubCommentPreviewResponse(
                preview.taskId(),
                preview.prNumber(),
                preview.prUrl(),
                preview.writebackCheck(),
                preview.totalFindings(),
                preview.commentableCount(),
                preview.blockedCount(),
                preview.publishedCount(),
                25,
                page,
                pageSize,
                commentableOnly,
                preview.items()
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
            lastPublicationPage = page;
            lastPublicationPageSize = pageSize;
            lastPublicationStatus = status;
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
                    null,
                    null,
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

    private final MockMvc mockMvc = MockMvcBuilders
        .standaloneSetup(new ReviewController(reviewService))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();

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
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.message").value("OK"))
            .andExpect(jsonPath("$.timestamp").exists())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items", hasSize(1)))
            .andExpect(jsonPath("$.data.items[0].id").value(512))
            .andExpect(jsonPath("$.data.items[0].prNumber").value(512))
            .andExpect(jsonPath("$.data.items[0].title").value("新增用户导出接口"))
            .andExpect(jsonPath("$.data.items[0].repository").value("spring-boot-demo"))
            .andExpect(jsonPath("$.data.items[0].organization").value("repo-guard-demo"))
            .andExpect(jsonPath("$.data.items[0].commit").value("a1b2c3d"))
            .andExpect(jsonPath("$.data.items[0].branch").value("main"))
            .andExpect(jsonPath("$.data.items[0].status").value("completed"))
            .andExpect(jsonPath("$.data.items[0].riskLevel").value("high"))
            .andExpect(jsonPath("$.data.items[0].mqRetries").value(0))
            .andExpect(jsonPath("$.data.items[0].llmStatus").value("completed"))
            .andExpect(jsonPath("$.data.items[0].source").value("github_pr_picker"))
            .andExpect(jsonPath("$.data.items[0].triggerSource").value("github_pr_picker"))
            .andExpect(jsonPath("$.data.items[0].createdAt").value("2025-05-31 14:32:21"))
            .andExpect(jsonPath("$.data.items[0].duration").value("2 分 48 秒"))
            .andExpect(jsonPath("$.data.items[0].humanReviewRequired").value(true))
            .andExpect(jsonPath("$.data.items[0].humanReviewStatus").value("pending"))
            .andExpect(jsonPath("$.data.items[0].humanReviewNote").value("Need owner confirmation"))
            .andExpect(jsonPath("$.data.items[0].humanReviewBy").value("review-lead"))
            .andExpect(jsonPath("$.data.items[0].humanReviewedAt").value("2026-06-12 11:00:00"));
        assertThat(lastListQuery.page()).isEqualTo(1);
        assertThat(lastListQuery.pageSize()).isEqualTo(20);
        assertThat(lastListQuery.status()).isEqualTo("completed");
        assertThat(lastListQuery.source()).isEqualTo("github_pr_picker");
        assertThat(lastListQuery.triggerSource()).isEqualTo("existing_reused");
    }

    @Test
    void listReviewsRejectsOverlongKeyword() throws Exception {
        mockMvc.perform(get("/api/v1/reviews")
                .param("keyword", "x".repeat(256)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void listRepositoriesReturnsLightweightOptions() throws Exception {
        mockMvc.perform(get("/api/v1/reviews/repositories"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data", hasSize(3)))
            .andExpect(jsonPath("$.data[0]").value("api"))
            .andExpect(jsonPath("$.data[1]").value("spring-boot-demo"))
            .andExpect(jsonPath("$.data[2]").value("web"));
    }

    @Test
    void getReviewDetailReturnsTaskDetail() throws Exception {
        mockMvc.perform(get("/api/v1/reviews/512"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.id").value(512))
            .andExpect(jsonPath("$.data.prNumber").value(512))
            .andExpect(jsonPath("$.data.title").value("新增用户导出接口"))
            .andExpect(jsonPath("$.data.repository").value("spring-boot-demo"))
            .andExpect(jsonPath("$.data.organization").value("repo-guard-demo"))
            .andExpect(jsonPath("$.data.commit").value("a1b2c3d"))
            .andExpect(jsonPath("$.data.branch").value("main"))
            .andExpect(jsonPath("$.data.status").value("completed"))
            .andExpect(jsonPath("$.data.riskLevel").value("high"))
            .andExpect(jsonPath("$.data.mqRetries").value(0))
            .andExpect(jsonPath("$.data.llmStatus").value("completed"))
            .andExpect(jsonPath("$.data.source").value("github_pr_picker"))
            .andExpect(jsonPath("$.data.triggerSource").value("github_pr_picker"))
            .andExpect(jsonPath("$.data.createdAt").value("2025-05-31 14:32:21"))
            .andExpect(jsonPath("$.data.duration").value("2 分 48 秒"))
            .andExpect(jsonPath("$.data.failureCategory").value("github_error"))
            .andExpect(jsonPath("$.data.failureReason").value("GitHub API rate limited"))
            .andExpect(jsonPath("$.data.failureSuggestion").value("Retry after rate limit resets"))
            .andExpect(jsonPath("$.data.prUrl").value("https://github.com/repo-guard-demo/spring-boot-demo/pull/512"))
            .andExpect(jsonPath("$.data.findings[0].id").value(1))
            .andExpect(jsonPath("$.data.findings[0].severity").value("high"))
            .andExpect(jsonPath("$.data.findings[0].file").value("src/App.java"))
            .andExpect(jsonPath("$.data.findings[0].line").value(12))
            .andExpect(jsonPath("$.data.findings[0].message").value("Use logger"))
            .andExpect(jsonPath("$.data.findings[0].recommendation").value("Replace stdout with logger"))
            .andExpect(jsonPath("$.data.findings[0].isBlocking").value(true))
            .andExpect(jsonPath("$.data.findings[0].reviewDimension").value("security"))
            .andExpect(jsonPath("$.data.findings[0].feedbackStatus").value("valid"))
            .andExpect(jsonPath("$.data.findingTotal").value(1))
            .andExpect(jsonPath("$.data.findingSeverityCounts.critical").value(1))
            .andExpect(jsonPath("$.data.findingSeverityCounts.high").value(2))
            .andExpect(jsonPath("$.data.findingSeverityCounts.medium").value(3))
            .andExpect(jsonPath("$.data.findingSeverityCounts.low").value(4))
            .andExpect(jsonPath("$.data.findingSeverityCounts.info").value(5))
            .andExpect(jsonPath("$.data.missingTests[0].file").value("UserExportControllerTest"))
            .andExpect(jsonPath("$.data.missingTestTotal").value(1))
            .andExpect(jsonPath("$.data.changedFiles[0].path").value("src/App.java"))
            .andExpect(jsonPath("$.data.changedFiles[0].changeType").value("modified"))
            .andExpect(jsonPath("$.data.changedFileTotal").value(1))
            .andExpect(jsonPath("$.data.timeline[0].label").value("GitHub diff fetched"))
            .andExpect(jsonPath("$.data.riskProfile.score").value(91))
            .andExpect(jsonPath("$.data.riskProfile.level").value("critical"))
            .andExpect(jsonPath("$.data.riskProfile.recommendHumanReview").value(true))
            .andExpect(jsonPath("$.data.prSummary.overallRisk").value("critical"))
            .andExpect(jsonPath("$.data.prSummary.recommendMerge").value(false))
            .andExpect(jsonPath("$.data.prSummary.githubCommentBody").value("RepoGuard summary body"))
            .andExpect(jsonPath("$.data.llm.status").value("completed"))
            .andExpect(jsonPath("$.data.llm.duration").value("2 分 48 秒"))
            .andExpect(jsonPath("$.data.llm.riskLevel").value("high"))
            .andExpect(jsonPath("$.data.chunkedReview.enabled").value(true))
            .andExpect(jsonPath("$.data.chunkedReview.chunkCount").value(3))
            .andExpect(jsonPath("$.data.chunkedReview.aggregateRisk").value("high"))
            .andExpect(jsonPath("$.data.chunkedReview.aggregateFindings").value(2))
            .andExpect(jsonPath("$.data.rabbitMq.consumeStatus").value("confirmed"))
            .andExpect(jsonPath("$.data.humanReviewRequired").value(true))
            .andExpect(jsonPath("$.data.humanReviewStatus").value("pending"))
            .andExpect(jsonPath("$.data.humanReviewNote").value("Need owner confirmation"))
            .andExpect(jsonPath("$.data.humanReviewBy").value("review-lead"))
            .andExpect(jsonPath("$.data.humanReviewedAt").value("2026-06-12 11:00:00"));
    }

    @Test
    void listReviewFindingsReturnsPagedItems() throws Exception {
        mockMvc.perform(get("/api/v1/reviews/512/findings")
                .param("page", "1")
                .param("pageSize", "20")
                .param("severity", "high")
                .param("category", "security")
                .param("feedbackStatus", "valid"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items", hasSize(1)))
            .andExpect(jsonPath("$.data.items[0].id").value(1))
            .andExpect(jsonPath("$.data.items[0].severity").value("high"))
            .andExpect(jsonPath("$.data.items[0].file").value("src/App.java"));
        assertThat(lastFindingsTaskId).isEqualTo(512L);
        assertThat(lastFindingsPage).isEqualTo(1);
        assertThat(lastFindingsPageSize).isEqualTo(20);
        assertThat(lastFindingsSeverity).isEqualTo("high");
        assertThat(lastFindingsCategory).isEqualTo("security");
        assertThat(lastFindingsFeedbackStatus).isEqualTo("valid");
    }

    @Test
    void listChangedFilesReturnsPagedItems() throws Exception {
        mockMvc.perform(get("/api/v1/reviews/512/changed-files")
                .param("page", "1")
                .param("pageSize", "20")
                .param("hasFinding", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items", hasSize(1)))
            .andExpect(jsonPath("$.data.items[0].path").value("src/App.java"))
            .andExpect(jsonPath("$.data.items[0].changeType").value("modified"));
        assertThat(lastChangedFilesTaskId).isEqualTo(512L);
        assertThat(lastChangedFilesPage).isEqualTo(1);
        assertThat(lastChangedFilesPageSize).isEqualTo(20);
        assertThat(lastChangedFilesHasFinding).isTrue();
    }

    @Test
    void listMissingTestsReturnsPagedItems() throws Exception {
        mockMvc.perform(get("/api/v1/reviews/512/missing-tests")
                .param("page", "1")
                .param("pageSize", "20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.items", hasSize(1)))
            .andExpect(jsonPath("$.data.items[0].file").value("UserExportControllerTest"))
            .andExpect(jsonPath("$.data.items[0].method").value("exportUsers"));
        assertThat(lastMissingTestsTaskId).isEqualTo(512L);
        assertThat(lastMissingTestsPage).isEqualTo(1);
        assertThat(lastMissingTestsPageSize).isEqualTo(20);
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
            .andExpect(jsonPath("$.data.latestTimeline.time").value("10:20:30"))
            .andExpect(jsonPath("$.data.latestTimeline.status").value("current"))
            .andExpect(jsonPath("$.data.humanReviewRequired").value(true))
            .andExpect(jsonPath("$.data.humanReviewStatus").value("pending"))
            .andExpect(jsonPath("$.data.humanReviewNote").value("Need owner confirmation"))
            .andExpect(jsonPath("$.data.humanReviewBy").value("review-lead"))
            .andExpect(jsonPath("$.data.humanReviewedAt").value("2026-06-12 11:00:00"));
    }

    @Test
    void listReviewTimelineReturnsLimitedItems() throws Exception {
        mockMvc.perform(get("/api/v1/reviews/512/timeline").param("limit", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.data", hasSize(1)))
            .andExpect(jsonPath("$.data[0].label").value("Review running"))
            .andExpect(jsonPath("$.data[0].time").value("10:20:00"))
            .andExpect(jsonPath("$.data[0].status").value("current"));
    }

    @Test
    void getGithubCommentPreviewReturnsCommentDrafts() throws Exception {
        mockMvc.perform(get("/api/v1/reviews/512/github-comments/preview"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.message").value("OK"))
            .andExpect(jsonPath("$.timestamp").exists())
            .andExpect(jsonPath("$.data.taskId").value(512))
            .andExpect(jsonPath("$.data.prNumber").value(512))
            .andExpect(jsonPath("$.data.prUrl").value("https://github.com/repo-guard-demo/spring-boot-demo/pull/512"))
            .andExpect(jsonPath("$.data.totalFindings").value(1))
            .andExpect(jsonPath("$.data.commentableCount").value(1))
            .andExpect(jsonPath("$.data.blockedCount").value(0))
            .andExpect(jsonPath("$.data.publishedCount").value(0))
            .andExpect(jsonPath("$.data.itemTotal").value(25))
            .andExpect(jsonPath("$.data.page").value(1))
            .andExpect(jsonPath("$.data.pageSize").value(20))
            .andExpect(jsonPath("$.data.commentableOnly").value(false))
            .andExpect(jsonPath("$.data.writebackCheck.status").value("ready"))
            .andExpect(jsonPath("$.data.writebackCheck.level").value("success"))
            .andExpect(jsonPath("$.data.writebackCheck.taskOwner").value("repo-guard-demo"))
            .andExpect(jsonPath("$.data.writebackCheck.taskRepository").value("spring-boot-demo"))
            .andExpect(jsonPath("$.data.writebackCheck.configuredOwner").value("repo-guard-demo"))
            .andExpect(jsonPath("$.data.writebackCheck.configuredRepository").value("spring-boot-demo"))
            .andExpect(jsonPath("$.data.writebackCheck.repositoryMatched").value(true))
            .andExpect(jsonPath("$.data.writebackCheck.tokenConfigured").value(true))
            .andExpect(jsonPath("$.data.writebackCheck.connectionHealthy").value(true))
            .andExpect(jsonPath("$.data.writebackCheck.messages[0]").value("GitHub 回写配置与当前任务仓库匹配。"))
            .andExpect(jsonPath("$.data.items", hasSize(1)))
            .andExpect(jsonPath("$.data.items[0].findingId").value(1))
            .andExpect(jsonPath("$.data.items[0].severity").value("low"))
            .andExpect(jsonPath("$.data.items[0].file").value("src/App.java"))
            .andExpect(jsonPath("$.data.items[0].line").value(12))
            .andExpect(jsonPath("$.data.items[0].message").value("Use logger"))
            .andExpect(jsonPath("$.data.items[0].recommendation").value("Replace stdout with logger"))
            .andExpect(jsonPath("$.data.items[0].commentBody").value("**RepoGuard LOW finding**\n\nUse logger\n\n**建议**：Replace stdout with logger"))
            .andExpect(jsonPath("$.data.items[0].commentable").value(true))
            .andExpect(jsonPath("$.data.items[0].targetType").value("line"))
            .andExpect(jsonPath("$.data.items[0].published").value(false))
            .andExpect(jsonPath("$.data.items[0].feedbackStatus").value("unreviewed"));
        assertThat(lastPreviewTaskId).isEqualTo(512L);
        assertThat(lastPreviewPage).isEqualTo(1);
        assertThat(lastPreviewPageSize).isEqualTo(20);
        assertThat(lastPreviewCommentableOnly).isFalse();
    }

    @Test
    void getGithubCommentPreviewForwardsPaginationParameters() throws Exception {
        mockMvc.perform(get("/api/v1/reviews/512/github-comments/preview")
                .param("page", "2")
                .param("pageSize", "1")
                .param("commentableOnly", "true"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.taskId").value(512))
            .andExpect(jsonPath("$.data.itemTotal").value(25))
            .andExpect(jsonPath("$.data.page").value(2))
            .andExpect(jsonPath("$.data.pageSize").value(1))
            .andExpect(jsonPath("$.data.commentableOnly").value(true));
        assertThat(lastPreviewTaskId).isEqualTo(512L);
        assertThat(lastPreviewPage).isEqualTo(2);
        assertThat(lastPreviewPageSize).isEqualTo(1);
        assertThat(lastPreviewCommentableOnly).isTrue();
    }

    @Test
    void publishGithubCommentsReturnsPublishSummary() throws Exception {
        mockMvc.perform(post("/api/v1/reviews/512/github-comments"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.data.taskId").value(512))
            .andExpect(jsonPath("$.data.totalFindings").value(1))
            .andExpect(jsonPath("$.data.attemptedCount").value(1))
            .andExpect(jsonPath("$.data.succeededCount").value(1))
            .andExpect(jsonPath("$.data.failedCount").value(0))
            .andExpect(jsonPath("$.data.skippedCount").value(0))
            .andExpect(jsonPath("$.data.items[0].findingId").value(1))
            .andExpect(jsonPath("$.data.items[0].file").value("src/App.java"))
            .andExpect(jsonPath("$.data.items[0].line").value(12))
            .andExpect(jsonPath("$.data.items[0].targetType").value("line"))
            .andExpect(jsonPath("$.data.items[0].success").value(true))
            .andExpect(jsonPath("$.data.items[0].status").value("published"))
            .andExpect(jsonPath("$.data.items[0].message").value("GitHub comment published"))
            .andExpect(jsonPath("$.data.items[0].url").value("https://github.com/repo/pull/1#discussion_r1"))
            .andExpect(jsonPath("$.data.items[0].githubCommentId").value(1001))
            .andExpect(jsonPath("$.data.items[0].publishedAt").value("2026-06-07 10:00:00"));
    }

    @Test
    void getGithubCommentPublicationHistoryReturnsBatches() throws Exception {
        mockMvc.perform(get("/api/v1/reviews/512/github-comments/publications")
                .param("page", "2")
                .param("pageSize", "10")
                .param("status", "completed"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.code").value("OK"))
            .andExpect(jsonPath("$.data.taskId").value(512))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.page").value(2))
            .andExpect(jsonPath("$.data.pageSize").value(10))
            .andExpect(jsonPath("$.data.status").value("completed"))
            .andExpect(jsonPath("$.data.batches", hasSize(1)))
            .andExpect(jsonPath("$.data.batches[0].batchId").value(10))
            .andExpect(jsonPath("$.data.batches[0].status").value("completed"))
            .andExpect(jsonPath("$.data.batches[0].totalFindings").value(1))
            .andExpect(jsonPath("$.data.batches[0].attemptedCount").value(1))
            .andExpect(jsonPath("$.data.batches[0].succeededCount").value(1))
            .andExpect(jsonPath("$.data.batches[0].failedCount").value(0))
            .andExpect(jsonPath("$.data.batches[0].skippedCount").value(0))
            .andExpect(jsonPath("$.data.batches[0].createdAt").value("2026-06-09 12:00:00"))
            .andExpect(jsonPath("$.data.batches[0].completedAt").value("2026-06-09 12:00:01"))
            .andExpect(jsonPath("$.data.batches[0].items[0].findingId").value(1))
            .andExpect(jsonPath("$.data.batches[0].items[0].file").value("src/App.java"))
            .andExpect(jsonPath("$.data.batches[0].items[0].line").value(12))
            .andExpect(jsonPath("$.data.batches[0].items[0].targetType").value("line"))
            .andExpect(jsonPath("$.data.batches[0].items[0].success").value(true))
            .andExpect(jsonPath("$.data.batches[0].items[0].status").value("published"))
            .andExpect(jsonPath("$.data.batches[0].items[0].url").value("https://github.com/repo/pull/1#discussion_r1"))
            .andExpect(jsonPath("$.data.batches[0].items[0].githubCommentId").value(1001))
            .andExpect(jsonPath("$.data.batches[0].items[0].publishedAt").value("2026-06-09 12:00:01"));
        assertThat(lastPublicationPage).isEqualTo(2);
        assertThat(lastPublicationPageSize).isEqualTo(10);
        assertThat(lastPublicationStatus).isEqualTo("completed");
    }

    @Test
    void getGithubCommentPublicationHistoryRejectsOverlongStatus() throws Exception {
        mockMvc.perform(get("/api/v1/reviews/512/github-comments/publications")
                .param("status", "x".repeat(33)))
            .andExpect(status().isBadRequest());
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
