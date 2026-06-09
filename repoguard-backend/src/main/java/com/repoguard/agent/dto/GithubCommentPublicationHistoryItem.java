package com.repoguard.agent.dto;

/**
 * 回写历史中的单条审查发现发布结果。
 */
public record GithubCommentPublicationHistoryItem(
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
