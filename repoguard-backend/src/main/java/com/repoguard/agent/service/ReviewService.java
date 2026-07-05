package com.repoguard.agent.service;

import com.repoguard.agent.dto.ChangedFileDto;
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
import com.repoguard.agent.dto.GithubCommentPreviewResponse;
import com.repoguard.agent.dto.GithubCommentPublicationHistoryResponse;
import com.repoguard.agent.dto.GithubCommentPublishResponse;
import com.repoguard.agent.dto.GithubPullRequestOptionsResponse;
import com.repoguard.agent.dto.FindingFeedbackRequest;
import com.repoguard.agent.dto.FindingFeedbackResponse;
import com.repoguard.agent.dto.HumanReviewRequest;
import com.repoguard.agent.dto.HumanReviewResponse;
import java.util.List;

public interface ReviewService {

    /**
     * 根据接口层筛选条件查询评审任务列表，并转换为数据库查询条件。
     */
    PageResponse<ReviewTaskListItem> listReviews(ReviewQuery query);

    List<String> listRepositories();

    /**
     * 加载单个评审任务，以及前端详情页需要的所有只读区块。
     */
    ReviewTaskDetail getReviewDetail(Long id);

    PageResponse<ReviewFindingDto> listReviewFindings(
        Long id,
        int page,
        int pageSize,
        String severity,
        String category,
        String feedbackStatus
    );

    PageResponse<ChangedFileDto> listChangedFiles(Long id, int page, int pageSize, Boolean hasFinding);

    PageResponse<MissingTestDto> listMissingTests(Long id, int page, int pageSize);

    ReviewTaskStatusResponse getReviewStatus(Long id);

    GithubCommentPreviewResponse getGithubCommentPreview(Long id);

    GithubCommentPreviewResponse getGithubCommentPreview(Long id, int page, int pageSize, boolean commentableOnly);

    /**
     * 将可回写审查发现发布到 GitHub，并记录本次操作的批次历史。
     */
    GithubCommentPublishResponse publishGithubComments(Long id);

    /**
     * 查询某个任务的 GitHub 评论回写历史，用于任务详情页追踪每次操作结果。
     */
    default GithubCommentPublicationHistoryResponse getGithubCommentPublicationHistory(Long id) {
        return getGithubCommentPublicationHistory(id, 1, 20, null);
    }

    GithubCommentPublicationHistoryResponse getGithubCommentPublicationHistory(Long id, int page, int pageSize, String status);

    GithubPullRequestOptionsResponse listConfiguredGithubPullRequests();

    ManualReviewResponse triggerManualReview(ManualReviewRequest request);

    HumanReviewResponse submitHumanReview(Long id, HumanReviewRequest request, String operator);

    FindingFeedbackResponse updateFindingFeedback(Long id, Long findingId, FindingFeedbackRequest request, String operator);

    ReviewRetryResponse retryReview(Long id);
}
