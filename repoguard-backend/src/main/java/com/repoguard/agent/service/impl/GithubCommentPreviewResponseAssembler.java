package com.repoguard.agent.service.impl;

import com.repoguard.agent.config.GithubIntegrationSettings;
import com.repoguard.agent.dto.GithubCommentPreviewItem;
import com.repoguard.agent.dto.GithubCommentPreviewResponse;
import com.repoguard.agent.dto.PrReviewSummaryDto;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.service.impl.GithubCommentPreviewDataLoader.GithubCommentPreviewData;
import com.repoguard.agent.service.impl.GithubCommentPreviewPublicationLoader.GithubCommentPreviewPublicationData;
import java.util.List;

public class GithubCommentPreviewResponseAssembler {

    private final GithubCommentWritebackCheckBuilder writebackCheckBuilder;
    private final GithubCommentPreviewItemBuilder previewItemBuilder;

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
        List<GithubCommentPreviewItem> items = new java.util.ArrayList<>();
        items.add(previewItemBuilder.buildPrSummaryItem(prSummary, publicationData.prSummaryPublication()));
        items.addAll(previewData.actionableFindings().stream()
            .map(finding -> previewItemBuilder.buildFindingItem(
                finding,
                previewData.changedFileByPath().get(finding.getFilePath()),
                publicationData.publicationByFindingId().get(finding.getId())
            ))
            .toList());

        int commentableCount = (int) items.stream().filter(GithubCommentPreviewItem::commentable).count();
        int publishedCount = (int) items.stream().filter(item -> Boolean.TRUE.equals(item.published())).count();
        return new GithubCommentPreviewResponse(
            task.getId(),
            task.getPrNumber(),
            task.getPrUrl(),
            writebackCheckBuilder.build(task, githubSettings),
            previewData.actionableFindings().size(),
            commentableCount,
            items.size() - commentableCount - publishedCount,
            items
        );
    }
}
