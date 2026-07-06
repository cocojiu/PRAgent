package com.repoguard.agent.service.impl;

import com.repoguard.agent.config.GithubIntegrationSettings;
import com.repoguard.agent.dto.GithubCommentPreviewItem;
import com.repoguard.agent.dto.GithubCommentPreviewResponse;
import com.repoguard.agent.dto.PrReviewSummaryDto;
import com.repoguard.agent.entity.GithubCommentPublication;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.service.impl.GithubCommentPreviewDataLoader.GithubCommentPreviewData;
import com.repoguard.agent.service.impl.GithubCommentPreviewDataLoader.GithubCommentPreviewPageData;
import com.repoguard.agent.service.impl.GithubCommentPreviewPublicationLoader.GithubCommentPreviewPublicationData;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class GithubCommentPreviewResponseAssembler {

    private final GithubCommentWritebackCheckBuilder writebackCheckBuilder;
    private final GithubCommentPreviewItemBuilder previewItemBuilder;
    private static final String FEEDBACK_UNREVIEWED = "UNREVIEWED";
    private static final String FEEDBACK_VALID = "VALID";

    @Autowired
    public GithubCommentPreviewResponseAssembler() {
        this(new GithubCommentWritebackCheckBuilder(), new GithubCommentPreviewItemBuilder());
    }

    GithubCommentPreviewResponseAssembler(
        GithubCommentWritebackCheckBuilder writebackCheckBuilder,
        GithubCommentPreviewItemBuilder previewItemBuilder
    ) {
        this.writebackCheckBuilder = writebackCheckBuilder;
        this.previewItemBuilder = previewItemBuilder;
    }

    public GithubCommentPreviewResponse assemble(
        ReviewTask task,
        GithubIntegrationSettings githubSettings,
        GithubCommentPreviewData previewData,
        PrReviewSummaryDto prSummary,
        GithubCommentPreviewPublicationData publicationData
    ) {
        return assembleInternal(task, githubSettings, previewData, prSummary, publicationData, 1, null, false);
    }

    public GithubCommentPreviewResponse assemble(
        ReviewTask task,
        GithubIntegrationSettings githubSettings,
        GithubCommentPreviewData previewData,
        PrReviewSummaryDto prSummary,
        GithubCommentPreviewPublicationData publicationData,
        int page,
        int pageSize,
        boolean commentableOnly
    ) {
        return assembleInternal(task, githubSettings, previewData, prSummary, publicationData, page, pageSize, commentableOnly);
    }

    public GithubCommentPreviewResponse assemblePaged(
        ReviewTask task,
        GithubIntegrationSettings githubSettings,
        GithubCommentPreviewPageData previewData,
        PrReviewSummaryDto prSummary,
        GithubCommentPreviewPublicationData publicationData,
        boolean includePrSummary,
        int totalFindings,
        int commentableCount,
        int blockedCount,
        int publishedCount,
        int itemTotal,
        int page,
        int pageSize,
        boolean commentableOnly
    ) {
        List<PreviewCandidate> candidates = buildPagedCandidates(previewData, publicationData, includePrSummary);
        List<GithubCommentPreviewItem> items = candidates.stream()
            .map(candidate -> buildPreviewItem(candidate, previewData, prSummary, publicationData))
            .toList();
        return new GithubCommentPreviewResponse(
            task.getId(),
            task.getPrNumber(),
            task.getPrUrl(),
            writebackCheckBuilder.build(task, githubSettings),
            totalFindings,
            commentableCount,
            blockedCount,
            publishedCount,
            itemTotal,
            Math.max(1, page),
            Math.max(1, pageSize),
            commentableOnly,
            items
        );
    }

    private GithubCommentPreviewResponse assembleInternal(
        ReviewTask task,
        GithubIntegrationSettings githubSettings,
        GithubCommentPreviewData previewData,
        PrReviewSummaryDto prSummary,
        GithubCommentPreviewPublicationData publicationData,
        int page,
        Integer pageSize,
        boolean commentableOnly
    ) {
        List<PreviewCandidate> candidates = buildCandidates(previewData, publicationData);
        int commentableCount = (int) candidates.stream().filter(PreviewCandidate::commentable).count();
        int publishedCount = (int) candidates.stream().filter(PreviewCandidate::published).count();
        int blockedCount = candidates.size() - commentableCount - publishedCount;

        List<PreviewCandidate> filteredCandidates = candidates.stream()
            .filter(candidate -> !commentableOnly || candidate.commentable())
            .toList();
        List<PreviewCandidate> pageCandidates = pageSize == null
            ? filteredCandidates
            : pageSlice(filteredCandidates, page, pageSize);
        List<GithubCommentPreviewItem> items = pageCandidates.stream()
            .map(candidate -> buildPreviewItem(candidate, previewData, prSummary, publicationData))
            .toList();
        int responsePageSize = pageSize == null ? items.size() : Math.max(1, pageSize);
        return new GithubCommentPreviewResponse(
            task.getId(),
            task.getPrNumber(),
            task.getPrUrl(),
            writebackCheckBuilder.build(task, githubSettings),
            previewData.actionableFindings().size(),
            commentableCount,
            blockedCount,
            publishedCount,
            filteredCandidates.size(),
            Math.max(1, page),
            responsePageSize,
            commentableOnly,
            items
        );
    }

    private List<PreviewCandidate> buildCandidates(
        GithubCommentPreviewData previewData,
        GithubCommentPreviewPublicationData publicationData
    ) {
        List<PreviewCandidate> candidates = new ArrayList<>();
        candidates.add(PreviewCandidate.prSummary(publicationData.prSummaryPublication()));
        candidates.addAll(previewData.actionableFindings().stream()
            .map(finding -> PreviewCandidate.finding(
                finding,
                publicationData.publicationByFindingId().get(finding.getId())
            ))
            .toList());
        return candidates;
    }

    private List<PreviewCandidate> buildPagedCandidates(
        GithubCommentPreviewPageData previewData,
        GithubCommentPreviewPublicationData publicationData,
        boolean includePrSummary
    ) {
        List<PreviewCandidate> candidates = new ArrayList<>();
        if (includePrSummary) {
            candidates.add(PreviewCandidate.prSummary(publicationData.prSummaryPublication()));
        }
        candidates.addAll(previewData.pageFindings().stream()
            .map(finding -> PreviewCandidate.finding(
                finding,
                publicationData.publicationByFindingId().get(finding.getId())
            ))
            .toList());
        return candidates;
    }

    private List<PreviewCandidate> pageSlice(List<PreviewCandidate> candidates, int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, pageSize);
        long offset = (long) (safePage - 1) * safePageSize;
        int fromIndex = offset >= candidates.size() ? candidates.size() : (int) offset;
        int toIndex = Math.min(fromIndex + safePageSize, candidates.size());
        return candidates.subList(fromIndex, toIndex);
    }

    private GithubCommentPreviewItem buildPreviewItem(
        PreviewCandidate candidate,
        GithubCommentPreviewData previewData,
        PrReviewSummaryDto prSummary,
        GithubCommentPreviewPublicationData publicationData
    ) {
        if (candidate.prSummary()) {
            return previewItemBuilder.buildPrSummaryItem(prSummary, publicationData.prSummaryPublication());
        }
        return previewItemBuilder.buildFindingItem(
            candidate.finding(),
            previewData.changedFileByPath().get(candidate.finding().getFilePath()),
            candidate.publication()
        );
    }

    private GithubCommentPreviewItem buildPreviewItem(
        PreviewCandidate candidate,
        GithubCommentPreviewPageData previewData,
        PrReviewSummaryDto prSummary,
        GithubCommentPreviewPublicationData publicationData
    ) {
        if (candidate.prSummary()) {
            return previewItemBuilder.buildPrSummaryItem(prSummary, publicationData.prSummaryPublication());
        }
        return previewItemBuilder.buildFindingItem(
            candidate.finding(),
            previewData.changedFileByPath().get(candidate.finding().getFilePath()),
            candidate.publication()
        );
    }

    private record PreviewCandidate(
        ReviewFinding finding,
        GithubCommentPublication publication,
        boolean prSummary
    ) {
        static PreviewCandidate prSummary(GithubCommentPublication publication) {
            return new PreviewCandidate(null, publication, true);
        }

        static PreviewCandidate finding(
            ReviewFinding finding,
            GithubCommentPublication publication
        ) {
            return new PreviewCandidate(finding, publication, false);
        }

        boolean published() {
            return publication != null
                && Boolean.TRUE.equals(publication.getSuccess())
                && StringUtils.hasText(publication.getGithubUrl());
        }

        boolean commentable() {
            if (published()) {
                return false;
            }
            if (prSummary) {
                return true;
            }
            String feedbackStatus = StringUtils.hasText(finding.getFeedbackStatus())
                ? finding.getFeedbackStatus().trim().toUpperCase(Locale.ROOT)
                : FEEDBACK_UNREVIEWED;
            return FEEDBACK_UNREVIEWED.equals(feedbackStatus) || FEEDBACK_VALID.equals(feedbackStatus);
        }
    }
}
