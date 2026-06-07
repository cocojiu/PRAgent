package com.repoguard.agent.github;

public record GithubReviewCommentResult(
    Long findingId,
    String path,
    Integer line,
    String targetType,
    Boolean success,
    String status,
    String message,
    String url,
    Long commentId
) {
}
