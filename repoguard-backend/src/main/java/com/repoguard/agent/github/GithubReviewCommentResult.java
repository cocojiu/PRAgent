package com.repoguard.agent.github;

public record GithubReviewCommentResult(
    Long findingId,
    String path,
    Integer line,
    Boolean success,
    String status,
    String message,
    String url
) {
}
