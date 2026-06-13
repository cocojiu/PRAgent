package com.repoguard.agent.dto;

public record GithubCommentPreviewItem(
    Long findingId,
    String severity,
    String file,
    Integer line,
    String message,
    String recommendation,
    String commentBody,
    Boolean commentable,
    String targetType,
    String reason,
    Boolean published,
    String publicationStatus,
    String publicationUrl,
    String publicationMessage,
    String publishedAt,
    String feedbackStatus
) {
    public GithubCommentPreviewItem(
        Long findingId,
        String severity,
        String file,
        Integer line,
        String message,
        String recommendation,
        String commentBody,
        Boolean commentable,
        String targetType,
        String reason,
        Boolean published,
        String publicationStatus,
        String publicationUrl,
        String publicationMessage,
        String publishedAt
    ) {
        this(
            findingId,
            severity,
            file,
            line,
            message,
            recommendation,
            commentBody,
            commentable,
            targetType,
            reason,
            published,
            publicationStatus,
            publicationUrl,
            publicationMessage,
            publishedAt,
            "unreviewed"
        );
    }
}
