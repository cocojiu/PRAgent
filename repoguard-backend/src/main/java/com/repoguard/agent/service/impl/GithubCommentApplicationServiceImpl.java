package com.repoguard.agent.service.impl;

import com.repoguard.agent.config.GithubIntegrationProvider;
import com.repoguard.agent.dto.GithubCommentPublicationHistoryResponse;
import com.repoguard.agent.dto.GithubCommentPreviewResponse;
import com.repoguard.agent.dto.GithubCommentPublishResponse;
import com.repoguard.agent.github.GithubPullRequestClient;
import com.repoguard.agent.github.GithubWritebackFailureClassifier;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchItemMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.notification.NotificationDispatchService;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.review.PrReviewSummaryBuilder;
import com.repoguard.agent.review.ReviewRiskProfileBuilder;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.service.GithubCommentApplicationService;
import com.repoguard.agent.service.GithubCommentHistoryQueryService;
import com.repoguard.agent.service.GithubCommentPreviewService;
import com.repoguard.agent.service.GithubCommentPublishService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class GithubCommentApplicationServiceImpl implements GithubCommentApplicationService {

    private final GithubCommentPreviewService previewService;
    private final GithubCommentPublishService publishService;
    private final GithubCommentHistoryQueryService historyQueryService;

    public GithubCommentApplicationServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ChangedFileMapper changedFileMapper,
        ReviewFindingMapper reviewFindingMapper,
        GithubCommentPublicationMapper githubCommentPublicationMapper,
        GithubCommentPublicationBatchMapper githubCommentPublicationBatchMapper,
        GithubCommentPublicationBatchItemMapper githubCommentPublicationBatchItemMapper,
        GithubIntegrationProvider githubIntegrationProvider,
        GithubPullRequestClient githubPullRequestClient,
        RepoGuardMetrics metrics,
        NotificationDispatchService notificationDispatchService
    ) {
        this(
            createPreviewService(
                reviewTaskMapper,
                changedFileMapper,
                reviewFindingMapper,
                githubCommentPublicationMapper,
                githubIntegrationProvider
            ),
            createPublishService(
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
                null
            ),
            createHistoryQueryService(
                reviewTaskMapper,
                githubCommentPublicationBatchMapper,
                githubCommentPublicationBatchItemMapper
            )
        );
    }

    public GithubCommentApplicationServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ChangedFileMapper changedFileMapper,
        ReviewFindingMapper reviewFindingMapper,
        GithubCommentPublicationMapper githubCommentPublicationMapper,
        GithubCommentPublicationBatchMapper githubCommentPublicationBatchMapper,
        GithubCommentPublicationBatchItemMapper githubCommentPublicationBatchItemMapper,
        GithubIntegrationProvider githubIntegrationProvider,
        GithubPullRequestClient githubPullRequestClient,
        RepoGuardMetrics metrics,
        NotificationDispatchService notificationDispatchService,
        ReviewTaskStateMachine reviewTaskStateMachine
    ) {
        this(
            createPreviewService(
                reviewTaskMapper,
                changedFileMapper,
                reviewFindingMapper,
                githubCommentPublicationMapper,
                githubIntegrationProvider
            ),
            createPublishService(
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
                reviewTaskStateMachine
            ),
            createHistoryQueryService(
                reviewTaskMapper,
                githubCommentPublicationBatchMapper,
                githubCommentPublicationBatchItemMapper
            )
        );
    }

    @Autowired
    public GithubCommentApplicationServiceImpl(
        GithubCommentPreviewService previewService,
        GithubCommentPublishService publishService,
        GithubCommentHistoryQueryService historyQueryService
    ) {
        this.previewService = previewService;
        this.publishService = publishService;
        this.historyQueryService = historyQueryService;
    }

    @Override
    public GithubCommentPreviewResponse getGithubCommentPreview(Long taskId) {
        return previewService.getPreview(taskId);
    }

    @Override
    public GithubCommentPreviewResponse getGithubCommentPreview(
        Long taskId,
        int page,
        int pageSize,
        boolean commentableOnly
    ) {
        return previewService.getPreview(taskId, page, pageSize, commentableOnly);
    }

    @Override
    public GithubCommentPublishResponse publishGithubComments(Long taskId) {
        return publishService.publishGithubComments(taskId);
    }

    @Override
    public GithubCommentPublicationHistoryResponse getGithubCommentPublicationHistory(
        Long taskId,
        int page,
        int pageSize,
        String status
    ) {
        return historyQueryService.getPublicationHistory(taskId, page, pageSize, status);
    }

    private static GithubCommentPreviewService createPreviewService(
        ReviewTaskMapper reviewTaskMapper,
        ChangedFileMapper changedFileMapper,
        ReviewFindingMapper reviewFindingMapper,
        GithubCommentPublicationMapper githubCommentPublicationMapper,
        GithubIntegrationProvider githubIntegrationProvider
    ) {
        return new GithubCommentPreviewServiceImpl(
            reviewTaskMapper,
            changedFileMapper,
            reviewFindingMapper,
            githubCommentPublicationMapper,
            githubIntegrationProvider,
            new ReviewRiskProfileBuilder(),
            new PrReviewSummaryBuilder()
        );
    }

    private static GithubCommentPublishService createPublishService(
        ReviewTaskMapper reviewTaskMapper,
        ChangedFileMapper changedFileMapper,
        ReviewFindingMapper reviewFindingMapper,
        GithubCommentPublicationMapper githubCommentPublicationMapper,
        GithubCommentPublicationBatchMapper githubCommentPublicationBatchMapper,
        GithubCommentPublicationBatchItemMapper githubCommentPublicationBatchItemMapper,
        GithubIntegrationProvider githubIntegrationProvider,
        GithubPullRequestClient githubPullRequestClient,
        RepoGuardMetrics metrics,
        NotificationDispatchService notificationDispatchService,
        ReviewTaskStateMachine reviewTaskStateMachine
    ) {
        return new GithubCommentPublishServiceImpl(
            reviewTaskMapper,
            githubCommentPublicationMapper,
            githubCommentPublicationBatchMapper,
            githubCommentPublicationBatchItemMapper,
            githubPullRequestClient,
            metrics,
            notificationDispatchService,
            reviewTaskStateMachine,
            new GithubWritebackFailureClassifier(),
            createPreviewService(
                reviewTaskMapper,
                changedFileMapper,
                reviewFindingMapper,
                githubCommentPublicationMapper,
                githubIntegrationProvider
            )
        );
    }

    private static GithubCommentHistoryQueryService createHistoryQueryService(
        ReviewTaskMapper reviewTaskMapper,
        GithubCommentPublicationBatchMapper githubCommentPublicationBatchMapper,
        GithubCommentPublicationBatchItemMapper githubCommentPublicationBatchItemMapper
    ) {
        return new GithubCommentHistoryQueryServiceImpl(
            reviewTaskMapper,
            githubCommentPublicationBatchMapper,
            githubCommentPublicationBatchItemMapper,
            new GithubCommentPublicationHistoryAssembler(new GithubWritebackFailureClassifier())
        );
    }
}
