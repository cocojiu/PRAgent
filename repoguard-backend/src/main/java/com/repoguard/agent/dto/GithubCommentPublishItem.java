package com.repoguard.agent.dto;

public record GithubCommentPublishItem(
    Long findingId,
    String file,
    Integer line,
    String targetType,
    Boolean success,
    String status,
    String message,
    String failureCategory,
    String failureReason,
    String failureSuggestion,
    String url,
    Long githubCommentId,
    String publishedAt
) {
}
