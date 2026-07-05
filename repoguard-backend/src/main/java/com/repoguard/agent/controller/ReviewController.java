package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.dto.ChangedFileDto;
import com.repoguard.agent.dto.FindingFeedbackRequest;
import com.repoguard.agent.dto.FindingFeedbackResponse;
import com.repoguard.agent.dto.GithubCommentPreviewResponse;
import com.repoguard.agent.dto.GithubCommentPublicationHistoryResponse;
import com.repoguard.agent.dto.GithubCommentPublishResponse;
import com.repoguard.agent.dto.GithubPullRequestOptionsResponse;
import com.repoguard.agent.dto.HumanReviewRequest;
import com.repoguard.agent.dto.HumanReviewResponse;
import com.repoguard.agent.dto.ManualReviewRequest;
import com.repoguard.agent.dto.ManualReviewResponse;
import com.repoguard.agent.dto.MissingTestDto;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.ReviewQuery;
import com.repoguard.agent.dto.ReviewFindingDto;
import com.repoguard.agent.dto.ReviewRetryResponse;
import com.repoguard.agent.dto.ReviewTaskDetail;
import com.repoguard.agent.dto.ReviewTaskListItem;
import com.repoguard.agent.dto.ReviewTaskStatusResponse;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.security.AuthTokenFilter;
import com.repoguard.agent.security.AuthTokenService;
import com.repoguard.agent.security.RequireRole;
import com.repoguard.agent.service.ReviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/reviews")
@ApiRuntimeEnabled
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * 查询评审任务列表。
     *
     * <p>支持仓库、状态、风险等级和关键字筛选，并在接口边界限制分页大小。
     */
    @GetMapping
    public ApiResponse<PageResponse<ReviewTaskListItem>> listReviews(
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
        @RequestParam(required = false) @Size(max = 128) String repository,
        @RequestParam(required = false) @Size(max = 32) String status,
        @RequestParam(required = false) @Size(max = 32) String riskLevel,
        @RequestParam(required = false) @Size(max = 64) String source,
        @RequestParam(required = false) @Size(max = 64) String triggerSource,
        @RequestParam(required = false) @Size(max = 255) String keyword
    ) {
        ReviewQuery query = new ReviewQuery(
            page,
            pageSize,
            checkedParam("repository", repository, 128),
            checkedParam("status", status, 32),
            checkedParam("riskLevel", riskLevel, 32),
            checkedParam("source", source, 64),
            checkedParam("triggerSource", triggerSource, 64),
            checkedParam("keyword", keyword, 255)
        );
        return ApiResponse.ok(reviewService.listReviews(query));
    }

    @GetMapping("/repositories")
    public ApiResponse<List<String>> listRepositories() {
        return ApiResponse.ok(reviewService.listRepositories());
    }

    /**
     * 返回 PR 评审详情页需要的完整只读数据。
     */
    @GetMapping("/{id}")
    public ApiResponse<ReviewTaskDetail> getReviewDetail(@PathVariable @Min(1) Long id) {
        return ApiResponse.ok(reviewService.getReviewDetail(id));
    }

    @GetMapping("/{id}/findings")
    public ApiResponse<PageResponse<ReviewFindingDto>> listReviewFindings(
        @PathVariable @Min(1) Long id,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
        @RequestParam(required = false) @Size(max = 32) String severity,
        @RequestParam(required = false) @Size(max = 64) String category,
        @RequestParam(required = false) @Size(max = 32) String feedbackStatus
    ) {
        return ApiResponse.ok(reviewService.listReviewFindings(
            id,
            page,
            pageSize,
            checkedParam("severity", severity, 32),
            checkedParam("category", category, 64),
            checkedParam("feedbackStatus", feedbackStatus, 32)
        ));
    }

    @GetMapping("/{id}/changed-files")
    public ApiResponse<PageResponse<ChangedFileDto>> listChangedFiles(
        @PathVariable @Min(1) Long id,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
        @RequestParam(required = false) Boolean hasFinding
    ) {
        return ApiResponse.ok(reviewService.listChangedFiles(id, page, pageSize, hasFinding));
    }

    @GetMapping("/{id}/missing-tests")
    public ApiResponse<PageResponse<MissingTestDto>> listMissingTests(
        @PathVariable @Min(1) Long id,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ApiResponse.ok(reviewService.listMissingTests(id, page, pageSize));
    }

    @GetMapping("/{id}/status")
    public ApiResponse<ReviewTaskStatusResponse> getReviewStatus(@PathVariable @Min(1) Long id) {
        return ApiResponse.ok(reviewService.getReviewStatus(id));
    }

    @GetMapping("/{id}/github-comments/preview")
    public ApiResponse<GithubCommentPreviewResponse> getGithubCommentPreview(
        @PathVariable @Min(1) Long id,
        @RequestParam(required = false) @Min(1) Integer page,
        @RequestParam(required = false) @Min(1) @Max(100) Integer pageSize,
        @RequestParam(defaultValue = "false") boolean commentableOnly
    ) {
        if (page == null && pageSize == null && !commentableOnly) {
            return ApiResponse.ok(reviewService.getGithubCommentPreview(id));
        }
        return ApiResponse.ok(reviewService.getGithubCommentPreview(
            id,
            page == null ? 1 : page,
            pageSize == null ? 20 : pageSize,
            commentableOnly
        ));
    }

    /**
     * 执行真实 GitHub 评论回写，并返回本次操作结果。
     */
    @PostMapping("/{id}/github-comments")
    @RequireRole("ADMIN")
    public ApiResponse<GithubCommentPublishResponse> publishGithubComments(@PathVariable @Min(1) Long id) {
        return ApiResponse.ok(reviewService.publishGithubComments(id));
    }

    /**
     * 返回历史回写批次，辅助前端展示成功、失败、跳过和降级为 PR 总评评论等状态。
     */
    @GetMapping("/{id}/github-comments/publications")
    public ApiResponse<GithubCommentPublicationHistoryResponse> getGithubCommentPublicationHistory(
        @PathVariable @Min(1) Long id,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
        @RequestParam(required = false) @Size(max = 32) String status
    ) {
        return ApiResponse.ok(reviewService.getGithubCommentPublicationHistory(
            id,
            page,
            pageSize,
            checkedParam("status", status, 32)
        ));
    }

    @GetMapping("/github/pull-requests")
    public ApiResponse<GithubPullRequestOptionsResponse> listConfiguredGithubPullRequests() {
        return ApiResponse.ok(reviewService.listConfiguredGithubPullRequests());
    }

    @PostMapping("/manual")
    @RequireRole("ADMIN")
    public ApiResponse<ManualReviewResponse> triggerManualReview(@Valid @RequestBody ManualReviewRequest request) {
        return ApiResponse.ok(reviewService.triggerManualReview(request));
    }

    @PostMapping("/{id}/human-review")
    @RequireRole("ADMIN")
    public ApiResponse<HumanReviewResponse> submitHumanReview(
        HttpServletRequest httpRequest,
        @PathVariable @Min(1) Long id,
        @Valid @RequestBody HumanReviewRequest request
    ) {
        return ApiResponse.ok(reviewService.submitHumanReview(id, request, authenticatedUsername(httpRequest)));
    }

    @PostMapping("/{id}/findings/{findingId}/feedback")
    @RequireRole("ADMIN")
    public ApiResponse<FindingFeedbackResponse> updateFindingFeedback(
        HttpServletRequest httpRequest,
        @PathVariable @Min(1) Long id,
        @PathVariable @Min(1) Long findingId,
        @Valid @RequestBody FindingFeedbackRequest request
    ) {
        return ApiResponse.ok(reviewService.updateFindingFeedback(id, findingId, request, authenticatedUsername(httpRequest)));
    }

    @PostMapping("/{id}/retry")
    @RequireRole("ADMIN")
    public ApiResponse<ReviewRetryResponse> retryReview(@PathVariable @Min(1) Long id) {
        return ApiResponse.ok(reviewService.retryReview(id));
    }

    private String authenticatedUsername(HttpServletRequest request) {
        Object authenticatedUser = request.getAttribute(AuthTokenFilter.AUTHENTICATED_USER_ATTRIBUTE);
        if (!(authenticatedUser instanceof AuthTokenService.AuthenticatedUser user)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Authentication token is required");
        }
        return user.username();
    }

    private String checkedParam(String name, String value, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, name + " must be at most " + maxLength + " characters");
        }
        return value;
    }
}
