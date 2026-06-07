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
    String publishedAt
) {
}
