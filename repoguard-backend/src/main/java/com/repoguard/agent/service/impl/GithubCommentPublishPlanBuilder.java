package com.repoguard.agent.service.impl;

import com.repoguard.agent.dto.GithubCommentPreviewItem;
import com.repoguard.agent.dto.GithubCommentPreviewResponse;
import com.repoguard.agent.dto.GithubCommentPublishItem;
import com.repoguard.agent.github.GithubReviewCommentDraft;
import org.springframework.stereotype.Component;

/**
 * Converts a comment preview into a concrete publish plan.
 */
@Component
public class GithubCommentPublishPlanBuilder {

    public GithubCommentPublishPlan build(GithubCommentPreviewResponse preview) {
        return new GithubCommentPublishPlan(
            buildDrafts(preview),
            buildSkippedItems(preview)
        );
    }

    private java.util.List<GithubReviewCommentDraft> buildDrafts(GithubCommentPreviewResponse preview) {
        return preview.items().stream()
            .filter(GithubCommentPreviewItem::commentable)
            .map(item -> new GithubReviewCommentDraft(
                item.findingId(),
                item.file(),
                item.line(),
                item.commentBody(),
                item.targetType()
            ))
            .toList();
    }

    private java.util.List<GithubCommentPublishItem> buildSkippedItems(GithubCommentPreviewResponse preview) {
        return preview.items().stream()
            .filter(item -> !item.commentable())
            .map(item -> new GithubCommentPublishItem(
                item.findingId(),
                item.file(),
                item.line(),
                item.targetType(),
                Boolean.TRUE.equals(item.published()),
                Boolean.TRUE.equals(item.published()) ? "already_published" : "skipped",
                Boolean.TRUE.equals(item.published()) ? "GitHub comment already published" : item.reason(),
                null,
                null,
                null,
                item.publicationUrl(),
                null,
                item.publishedAt()
            ))
            .toList();
    }
}
