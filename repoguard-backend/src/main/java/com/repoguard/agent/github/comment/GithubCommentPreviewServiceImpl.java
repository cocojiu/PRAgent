package com.repoguard.agent.github.comment;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.GithubCommentPreviewResponse;
import com.repoguard.agent.dto.PrRiskProfileDto;
import com.repoguard.agent.dto.PrReviewSummaryDto;
import com.repoguard.agent.dto.ReviewTaskListItem;
import com.repoguard.agent.entity.GithubCommentPublication;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubIntegrationProvider;
import com.repoguard.agent.github.GithubIntegrationSettings;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.PrReviewSummaryBuilder;
import com.repoguard.agent.review.ReviewRiskProfileBuilder;
import com.repoguard.agent.review.task.ReviewFailureSummaryResolver.ReviewFailureSummary;
import com.repoguard.agent.review.task.ReviewTaskListItemAssembler;
import com.repoguard.agent.service.GithubCommentPreviewService;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class GithubCommentPreviewServiceImpl implements GithubCommentPreviewService {

    private static final int DEFAULT_PREVIEW_PAGE = 1;
    private static final int DEFAULT_PREVIEW_PAGE_SIZE = 20;
    private static final ReviewFailureSummary NO_FAILURE_SUMMARY = new ReviewFailureSummary(null, null, null);

    private final ReviewTaskMapper reviewTaskMapper;
    private final GithubIntegrationProvider githubIntegrationProvider;
    private final ReviewRiskProfileBuilder riskProfileBuilder;
    private final PrReviewSummaryBuilder reviewSummaryBuilder;
    private final ReviewTaskListItemAssembler listItemAssembler;
    private final GithubCommentPreviewDataLoader previewDataLoader;
    private final GithubCommentPreviewPublicationLoader previewPublicationLoader;
    private final GithubCommentPreviewResponseAssembler responseAssembler;

    @Autowired
    public GithubCommentPreviewServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        GithubIntegrationProvider githubIntegrationProvider,
        ReviewRiskProfileBuilder riskProfileBuilder,
        PrReviewSummaryBuilder reviewSummaryBuilder,
        ReviewTaskListItemAssembler listItemAssembler,
        GithubCommentPreviewDataLoader previewDataLoader,
        GithubCommentPreviewPublicationLoader previewPublicationLoader,
        GithubCommentPreviewResponseAssembler responseAssembler
    ) {
        this.reviewTaskMapper = Objects.requireNonNull(reviewTaskMapper, "reviewTaskMapper");
        this.githubIntegrationProvider = Objects.requireNonNull(githubIntegrationProvider, "githubIntegrationProvider");
        this.riskProfileBuilder = Objects.requireNonNull(riskProfileBuilder, "riskProfileBuilder");
        this.reviewSummaryBuilder = Objects.requireNonNull(reviewSummaryBuilder, "reviewSummaryBuilder");
        this.listItemAssembler = Objects.requireNonNull(listItemAssembler, "listItemAssembler");
        this.previewDataLoader = Objects.requireNonNull(previewDataLoader, "previewDataLoader");
        this.previewPublicationLoader = Objects.requireNonNull(previewPublicationLoader, "previewPublicationLoader");
        this.responseAssembler = Objects.requireNonNull(responseAssembler, "responseAssembler");
    }

    @Override
    public GithubCommentPreviewResponse getPreview(Long taskId) {
        return getPreview(taskId, DEFAULT_PREVIEW_PAGE, DEFAULT_PREVIEW_PAGE_SIZE, false);
    }

    @Override
    public GithubCommentPreviewResponse getPreview(Long taskId, int page, int pageSize, boolean commentableOnly) {
        ReviewTask task = loadTask(taskId);
        return buildPagedPreview(taskId, task, page, pageSize, commentableOnly);
    }

    @Override
    public GithubCommentPreviewResponse getFullPreview(Long taskId) {
        ReviewTask task = loadTask(taskId);
        return buildFullPreview(taskId, task);
    }

    private GithubCommentPreviewResponse buildFullPreview(Long taskId, ReviewTask task) {
        var previewData = previewDataLoader.load(taskId);
        ReviewTaskListItem taskItem = listItemAssembler.assemble(task, NO_FAILURE_SUMMARY);
        PrRiskProfileDto riskProfile = riskProfileBuilder.build(
            taskItem,
            previewData.findings(),
            previewData.changedFiles()
        );
        PrReviewSummaryDto prSummary = reviewSummaryBuilder.build(
            taskItem,
            previewData.findings(),
            previewData.missingTests(),
            previewData.changedFiles(),
            riskProfile
        );
        var publicationData = previewPublicationLoader.load(taskId, previewData.actionableFindings());

        return responseAssembler.assemble(
            task,
            loadGithubSettings(task),
            previewData,
            prSummary,
            publicationData
        );
    }

    private ReviewTask loadTask(Long taskId) {
        ReviewTask task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + taskId);
        }
        return task;
    }

    private GithubCommentPreviewResponse buildPagedPreview(
        Long taskId,
        ReviewTask task,
        int page,
        int pageSize,
        boolean commentableOnly
    ) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, pageSize);
        GithubCommentPublication prSummaryPublication = previewPublicationLoader.loadPrSummaryPublication(taskId);
        boolean prSummaryPublished = published(prSummaryPublication);
        boolean prSummaryCommentable = !prSummaryPublished;
        boolean prSummaryVisible = !commentableOnly || prSummaryCommentable;
        long candidateOffset = (long) (safePage - 1) * safePageSize;
        boolean includePrSummary = prSummaryVisible && candidateOffset == 0;
        long findingOffset = Math.max(0L, candidateOffset - (prSummaryVisible ? 1L : 0L));
        int findingLimit = safePageSize - (includePrSummary ? 1 : 0);

        var previewData = previewDataLoader.loadPage(taskId, findingOffset, findingLimit, commentableOnly);
        ReviewTaskListItem taskItem = listItemAssembler.assemble(task, NO_FAILURE_SUMMARY);
        PrRiskProfileDto riskProfile = riskProfileBuilder.buildSummary(
            taskItem,
            previewData.severityCounts(),
            previewData.findingTotal(),
            previewData.changedFileTotal()
        );
        PrReviewSummaryDto prSummary = reviewSummaryBuilder.build(
            taskItem,
            previewData.findings(),
            java.util.List.of(),
            previewData.focusChangedFiles(),
            riskProfile,
            previewData.severityCounts(),
            previewData.findingTotal(),
            previewData.missingTestTotal(),
            previewData.changedFileTotal()
        );
        var publicationData = previewPublicationLoader.load(
            taskId,
            previewData.pageFindings(),
            prSummaryPublication
        );
        long publishedCount = previewData.publishedFindingCount() + (prSummaryPublished ? 1L : 0L);
        long commentableCount = previewData.commentableFindingCount() + (prSummaryCommentable ? 1L : 0L);
        long candidateTotal = previewData.findingTotal() + 1L;
        int responsePublishedCount = safeInt(publishedCount);
        int responseCommentableCount = safeInt(commentableCount);
        int responseBlockedCount = safeInt(Math.max(0L, candidateTotal - publishedCount - commentableCount));
        int responseItemTotal = commentableOnly ? responseCommentableCount : safeInt(candidateTotal);

        return responseAssembler.assemblePaged(
            task,
            loadGithubSettings(task),
            previewData,
            prSummary,
            publicationData,
            includePrSummary,
            safeInt(previewData.findingTotal()),
            responseCommentableCount,
            responseBlockedCount,
            responsePublishedCount,
            responseItemTotal,
            safePage,
            safePageSize,
            commentableOnly
        );
    }

    private GithubIntegrationSettings loadGithubSettings(ReviewTask task) {
        GithubIntegrationSettings settings = githubIntegrationProvider.getSettingsForRepository(
            task.getOrganization(),
            task.getRepository()
        );
        return settings == null ? githubIntegrationProvider.getSettings() : settings;
    }

    private boolean published(GithubCommentPublication publication) {
        return publication != null
            && Boolean.TRUE.equals(publication.getSuccess())
            && StringUtils.hasText(publication.getGithubUrl());
    }

    private int safeInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0L, value);
    }
}
