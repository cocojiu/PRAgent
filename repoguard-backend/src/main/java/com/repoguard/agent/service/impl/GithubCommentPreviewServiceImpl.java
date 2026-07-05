package com.repoguard.agent.service.impl;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.GithubIntegrationProvider;
import com.repoguard.agent.dto.GithubCommentPreviewResponse;
import com.repoguard.agent.dto.PrRiskProfileDto;
import com.repoguard.agent.dto.PrReviewSummaryDto;
import com.repoguard.agent.dto.ReviewTaskListItem;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.PrReviewSummaryBuilder;
import com.repoguard.agent.review.ReviewRiskProfileBuilder;
import com.repoguard.agent.service.GithubCommentPreviewService;
import com.repoguard.agent.service.impl.ReviewFailureSummaryResolver.ReviewFailureSummary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class GithubCommentPreviewServiceImpl implements GithubCommentPreviewService {

    private static final ReviewFailureSummary NO_FAILURE_SUMMARY = new ReviewFailureSummary(null, null, null);

    private final ReviewTaskMapper reviewTaskMapper;
    private final GithubIntegrationProvider githubIntegrationProvider;
    private final ReviewRiskProfileBuilder riskProfileBuilder;
    private final PrReviewSummaryBuilder reviewSummaryBuilder;
    private final ReviewTaskListItemAssembler listItemAssembler;
    private final GithubCommentPreviewDataLoader previewDataLoader;
    private final GithubCommentPreviewPublicationLoader previewPublicationLoader;
    private final GithubCommentPreviewResponseAssembler responseAssembler;

    public GithubCommentPreviewServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ChangedFileMapper changedFileMapper,
        ReviewFindingMapper reviewFindingMapper,
        GithubCommentPublicationMapper githubCommentPublicationMapper,
        GithubIntegrationProvider githubIntegrationProvider,
        ReviewRiskProfileBuilder riskProfileBuilder,
        PrReviewSummaryBuilder reviewSummaryBuilder
    ) {
        this(
            reviewTaskMapper,
            changedFileMapper,
            reviewFindingMapper,
            githubCommentPublicationMapper,
            githubIntegrationProvider,
            riskProfileBuilder,
            reviewSummaryBuilder,
            new ReviewTaskListItemAssembler(),
            new GithubCommentPreviewDataLoader(changedFileMapper, reviewFindingMapper),
            new GithubCommentPreviewPublicationLoader(githubCommentPublicationMapper),
            new GithubCommentPreviewResponseAssembler()
        );
    }

    @Autowired
    public GithubCommentPreviewServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ChangedFileMapper changedFileMapper,
        ReviewFindingMapper reviewFindingMapper,
        GithubCommentPublicationMapper githubCommentPublicationMapper,
        GithubIntegrationProvider githubIntegrationProvider,
        ReviewRiskProfileBuilder riskProfileBuilder,
        PrReviewSummaryBuilder reviewSummaryBuilder,
        ReviewTaskListItemAssembler listItemAssembler,
        GithubCommentPreviewDataLoader previewDataLoader,
        GithubCommentPreviewPublicationLoader previewPublicationLoader,
        GithubCommentPreviewResponseAssembler responseAssembler
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.githubIntegrationProvider = githubIntegrationProvider;
        this.riskProfileBuilder = riskProfileBuilder;
        this.reviewSummaryBuilder = reviewSummaryBuilder;
        this.listItemAssembler = listItemAssembler;
        this.previewDataLoader = previewDataLoader;
        this.previewPublicationLoader = previewPublicationLoader;
        this.responseAssembler = responseAssembler;
    }

    @Override
    public GithubCommentPreviewResponse getPreview(Long taskId) {
        return buildPreview(taskId, null, null, false);
    }

    @Override
    public GithubCommentPreviewResponse getPreview(Long taskId, int page, int pageSize, boolean commentableOnly) {
        return buildPreview(taskId, page, pageSize, commentableOnly);
    }

    private GithubCommentPreviewResponse buildPreview(Long taskId, Integer page, Integer pageSize, boolean commentableOnly) {
        ReviewTask task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + taskId);
        }

        var previewData = previewDataLoader.load(taskId);
        ReviewTaskListItem taskItem = listItemAssembler.assemble(task, NO_FAILURE_SUMMARY);
        PrRiskProfileDto riskProfile = riskProfileBuilder.build(taskItem, previewData.findings(), previewData.changedFiles());
        PrReviewSummaryDto prSummary = reviewSummaryBuilder.build(
            taskItem,
            previewData.findings(),
            previewData.missingTests(),
            previewData.changedFiles(),
            riskProfile
        );
        var publicationData = previewPublicationLoader.load(
            taskId,
            previewData.actionableFindings()
        );

        if (page == null || pageSize == null) {
            return responseAssembler.assemble(
                task,
                githubIntegrationProvider.getSettings(),
                previewData,
                prSummary,
                publicationData
            );
        }

        return responseAssembler.assemble(
            task,
            githubIntegrationProvider.getSettings(),
            previewData,
            prSummary,
            publicationData,
            page,
            pageSize,
            commentableOnly
        );
    }

}
