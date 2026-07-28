package com.repoguard.agent.github.comment;

import com.repoguard.agent.dto.GithubCommentPreviewItem;
import com.repoguard.agent.dto.GithubCommentPreviewResponse;
import com.repoguard.agent.dto.GithubCommentPublishItem;
import com.repoguard.agent.github.GithubCommentPublicationStatus;
import com.repoguard.agent.github.GithubReviewCommentDraft;
import org.springframework.stereotype.Component;

/**
 * Converts a comment preview into a concrete publish plan.
 */
@Component
public class GithubCommentPublishPlanBuilder {

    public GithubCommentPublishPlan build(GithubCommentPreviewResponse preview) {
        return build(preview.items());
    }

    public GithubCommentPublishPlan build(java.util.List<GithubCommentPreviewItem> items) {
        return new GithubCommentPublishPlan(
            buildDrafts(items),
            buildSkippedItems(items)
        );
    }

    private java.util.List<GithubReviewCommentDraft> buildDrafts(java.util.List<GithubCommentPreviewItem> items) {
        return items.stream()
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

    private java.util.List<GithubCommentPublishItem> buildSkippedItems(java.util.List<GithubCommentPreviewItem> items) {
        return items.stream()
            .filter(item -> !item.commentable())
            .map(item -> new GithubCommentPublishItem(
                item.findingId(),
                item.file(),
                item.line(),
                item.targetType(),
                Boolean.TRUE.equals(item.published()),
                Boolean.TRUE.equals(item.published())
                    ? GithubCommentPublicationStatus.ALREADY_PUBLISHED.code()
                    : GithubCommentPublicationStatus.SKIPPED.code(),
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
