package com.repoguard.agent.service;

import com.repoguard.agent.dto.ManualReviewRequest;
import com.repoguard.agent.dto.ManualReviewResponse;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.ReviewQuery;
import com.repoguard.agent.dto.ReviewRetryResponse;
import com.repoguard.agent.dto.ReviewTaskDetail;
import com.repoguard.agent.dto.ReviewTaskListItem;
import com.repoguard.agent.dto.GithubCommentPreviewResponse;
import com.repoguard.agent.dto.GithubCommentPublicationHistoryResponse;
import com.repoguard.agent.dto.GithubCommentPublishResponse;
import com.repoguard.agent.dto.GithubPullRequestOptionsResponse;

public interface ReviewService {

    /**
     * 根据接口层筛选条件查询评审任务列表，并转换为数据库查询条件。
     */
    PageResponse<ReviewTaskListItem> listReviews(ReviewQuery query);

    /**
     * 加载单个评审任务，以及前端详情页需要的所有只读区块。
     */
    ReviewTaskDetail getReviewDetail(Long id);

    GithubCommentPreviewResponse getGithubCommentPreview(Long id);

    /**
     * 将可回写审查发现发布到 GitHub，并记录本次操作的批次历史。
     */
    GithubCommentPublishResponse publishGithubComments(Long id);

    /**
     * 查询某个任务的 GitHub 评论回写历史，用于任务详情页追踪每次操作结果。
     */
    GithubCommentPublicationHistoryResponse getGithubCommentPublicationHistory(Long id);

    GithubPullRequestOptionsResponse listConfiguredGithubPullRequests();

    ManualReviewResponse triggerManualReview(ManualReviewRequest request);

    ReviewRetryResponse retryReview(Long id);
}
