package com.repoguard.agent.service.impl;

import com.repoguard.agent.config.CacheEvictionService;
import com.repoguard.agent.config.GithubIntegrationProvider;
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
import com.repoguard.agent.dto.ReviewTimelineItem;
import com.repoguard.agent.github.GithubPullRequestClient;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchItemMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.messaging.ReviewTaskPublisher;
import com.repoguard.agent.notification.NotificationDispatchService;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.service.FindingFeedbackService;
import com.repoguard.agent.service.GithubCommentApplicationService;
import com.repoguard.agent.service.GithubPullRequestOptionService;
import com.repoguard.agent.service.ReviewService;
import com.repoguard.agent.service.ReviewTaskCommandService;
import com.repoguard.agent.service.ReviewTaskQueryService;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewServiceImpl implements ReviewService {

    private final ReviewTaskMapper reviewTaskMapper;
    private final ChangedFileMapper changedFileMapper;
    private final ReviewFindingMapper reviewFindingMapper;
    private final GithubCommentPublicationMapper githubCommentPublicationMapper;
    private final GithubCommentPublicationBatchMapper githubCommentPublicationBatchMapper;
    private final GithubCommentPublicationBatchItemMapper githubCommentPublicationBatchItemMapper;
    private final ReviewTimelineMapper reviewTimelineMapper;
    private final ReviewTaskPublisher reviewTaskPublisher;
    private final GithubPullRequestClient githubPullRequestClient;
    private final RepoGuardMetrics metrics;
    private final NotificationDispatchService notificationDispatchService;
    private final CacheEvictionService cacheEvictionService;
    private final ReviewTaskQueryService reviewTaskQueryService;
    private final ReviewTaskCommandService reviewTaskCommandService;
    private final FindingFeedbackService findingFeedbackService;
    private final GithubCommentApplicationService githubCommentApplicationService;
    private final GithubPullRequestOptionService githubPullRequestOptionService;
    private final GithubIntegrationProvider githubIntegrationProvider;
    private final ReviewTaskStateMachine reviewTaskStateMachine;

    public ReviewServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ChangedFileMapper changedFileMapper,
        ReviewFindingMapper reviewFindingMapper,
        GithubCommentPublicationMapper githubCommentPublicationMapper,
        GithubCommentPublicationBatchMapper githubCommentPublicationBatchMapper,
        GithubCommentPublicationBatchItemMapper githubCommentPublicationBatchItemMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewTaskPublisher reviewTaskPublisher,
        GithubPullRequestClient githubPullRequestClient,
        GithubIntegrationProvider githubIntegrationProvider
    ) {
        this(
            reviewTaskMapper,
            changedFileMapper,
            reviewFindingMapper,
            githubCommentPublicationMapper,
            githubCommentPublicationBatchMapper,
            githubCommentPublicationBatchItemMapper,
            githubIntegrationProvider,
            reviewTimelineMapper,
            reviewTaskPublisher,
            githubPullRequestClient,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    @Autowired
    public ReviewServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ChangedFileMapper changedFileMapper,
        ReviewFindingMapper reviewFindingMapper,
        GithubCommentPublicationMapper githubCommentPublicationMapper,
        GithubCommentPublicationBatchMapper githubCommentPublicationBatchMapper,
        GithubCommentPublicationBatchItemMapper githubCommentPublicationBatchItemMapper,
        GithubIntegrationProvider githubIntegrationProvider,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewTaskPublisher reviewTaskPublisher,
        GithubPullRequestClient githubPullRequestClient,
        RepoGuardMetrics metrics,
        NotificationDispatchService notificationDispatchService,
        CacheEvictionService cacheEvictionService,
        ReviewTaskQueryService reviewTaskQueryService,
        ReviewTaskCommandService reviewTaskCommandService,
        FindingFeedbackService findingFeedbackService,
        GithubCommentApplicationService githubCommentApplicationService,
        GithubPullRequestOptionService githubPullRequestOptionService,
        ReviewTaskStateMachine reviewTaskStateMachine
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.changedFileMapper = changedFileMapper;
        this.reviewFindingMapper = reviewFindingMapper;
        this.githubCommentPublicationMapper = githubCommentPublicationMapper;
        this.githubCommentPublicationBatchMapper = githubCommentPublicationBatchMapper;
        this.githubCommentPublicationBatchItemMapper = githubCommentPublicationBatchItemMapper;
        this.githubIntegrationProvider = githubIntegrationProvider;
        this.reviewTaskStateMachine = reviewTaskStateMachine == null
            ? new ReviewTaskStateMachine()
            : reviewTaskStateMachine;
        this.reviewTimelineMapper = reviewTimelineMapper;
        this.reviewTaskPublisher = reviewTaskPublisher;
        this.githubPullRequestClient = githubPullRequestClient;
        this.metrics = metrics;
        this.notificationDispatchService = notificationDispatchService;
        this.cacheEvictionService = cacheEvictionService;
        this.reviewTaskQueryService = reviewTaskQueryService == null
            ? new ReviewTaskQueryServiceImpl(reviewTaskMapper, changedFileMapper, reviewFindingMapper, reviewTimelineMapper)
            : reviewTaskQueryService;
        this.reviewTaskCommandService = reviewTaskCommandService == null
            ? new ReviewTaskCommandServiceImpl(
                reviewTaskMapper,
                reviewTimelineMapper,
                reviewTaskPublisher,
                metrics,
                cacheEvictionService,
                this.reviewTaskStateMachine
            )
            : reviewTaskCommandService;
        this.findingFeedbackService = findingFeedbackService == null
            ? new FindingFeedbackServiceImpl(reviewTaskMapper, reviewFindingMapper, reviewTimelineMapper, cacheEvictionService)
            : findingFeedbackService;
        this.githubCommentApplicationService = githubCommentApplicationService == null
            ? new GithubCommentApplicationServiceImpl(
                reviewTaskMapper,
                changedFileMapper,
                reviewFindingMapper,
                githubCommentPublicationMapper,
                githubCommentPublicationBatchMapper,
                githubCommentPublicationBatchItemMapper,
                githubIntegrationProvider,
                githubPullRequestClient,
                metrics,
                notificationDispatchService,
                this.reviewTaskStateMachine
            )
            : githubCommentApplicationService;
        this.githubPullRequestOptionService = githubPullRequestOptionService == null
            ? new GithubPullRequestOptionServiceImpl(githubPullRequestClient, reviewTaskMapper)
            : githubPullRequestOptionService;
    }

    public ReviewServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ChangedFileMapper changedFileMapper,
        ReviewFindingMapper reviewFindingMapper,
        GithubCommentPublicationMapper githubCommentPublicationMapper,
        GithubCommentPublicationBatchMapper githubCommentPublicationBatchMapper,
        GithubCommentPublicationBatchItemMapper githubCommentPublicationBatchItemMapper,
        GithubIntegrationProvider githubIntegrationProvider,
        ReviewTimelineMapper reviewTimelineMapper,
        ReviewTaskPublisher reviewTaskPublisher,
        GithubPullRequestClient githubPullRequestClient,
        RepoGuardMetrics metrics
    ) {
        this(
            reviewTaskMapper,
            changedFileMapper,
            reviewFindingMapper,
            githubCommentPublicationMapper,
            githubCommentPublicationBatchMapper,
            githubCommentPublicationBatchItemMapper,
            githubIntegrationProvider,
            reviewTimelineMapper,
            reviewTaskPublisher,
            githubPullRequestClient,
            metrics,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }

    @Override
    public PageResponse<ReviewTaskListItem> listReviews(ReviewQuery query) {
        return reviewTaskQueryService.listReviews(query);
    }

    @Override
    public List<String> listRepositories() {
        return reviewTaskQueryService.listRepositories();
    }

    @Override
    public GithubCommentPublishResponse publishGithubComments(Long id) {
        return githubCommentApplicationService.publishGithubComments(id);
    }

    @Override
    public ReviewTaskDetail getReviewDetail(Long id) {
        return reviewTaskQueryService.getReviewDetail(id);
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
        return reviewTaskQueryService.listReviewFindings(id, page, pageSize, severity, category, feedbackStatus);
    }

    @Override
    public PageResponse<ChangedFileDto> listChangedFiles(Long id, int page, int pageSize, Boolean hasFinding) {
        return reviewTaskQueryService.listChangedFiles(id, page, pageSize, hasFinding);
    }

    @Override
    public PageResponse<MissingTestDto> listMissingTests(Long id, int page, int pageSize) {
        return reviewTaskQueryService.listMissingTests(id, page, pageSize);
    }

    @Override
    public List<ReviewTimelineItem> listReviewTimeline(Long id, int limit) {
        return reviewTaskQueryService.listReviewTimeline(id, limit);
    }

    @Override
    public ReviewTaskStatusResponse getReviewStatus(Long id) {
        return reviewTaskQueryService.getReviewStatus(id);
    }

    @Override
    public GithubCommentPreviewResponse getGithubCommentPreview(Long id) {
        return githubCommentApplicationService.getGithubCommentPreview(id);
    }

    @Override
    public GithubCommentPreviewResponse getGithubCommentPreview(
        Long id,
        int page,
        int pageSize,
        boolean commentableOnly
    ) {
        return githubCommentApplicationService.getGithubCommentPreview(id, page, pageSize, commentableOnly);
    }

    @Override
    public GithubCommentPublicationHistoryResponse getGithubCommentPublicationHistory(Long id, int page, int pageSize, String status) {
        return githubCommentApplicationService.getGithubCommentPublicationHistory(id, page, pageSize, status);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ManualReviewResponse triggerManualReview(ManualReviewRequest request) {
        return reviewTaskCommandService.triggerManualReview(request);
    }

    @Override
    @Transactional
    public HumanReviewResponse submitHumanReview(Long id, HumanReviewRequest request, String operator) {
        return reviewTaskCommandService.submitHumanReview(id, request, operator);
    }

    @Override
    @Transactional
    public FindingFeedbackResponse updateFindingFeedback(Long id, Long findingId, FindingFeedbackRequest request, String operator) {
        return findingFeedbackService.updateFindingFeedback(id, findingId, request, operator);
    }

    @Override
    @Transactional
    public ReviewRetryResponse retryReview(Long id) {
        return reviewTaskCommandService.retryReview(id);
    }

    @Override
    public GithubPullRequestOptionsResponse listConfiguredGithubPullRequests() {
        return githubPullRequestOptionService.listConfiguredGithubPullRequests();
    }

}
