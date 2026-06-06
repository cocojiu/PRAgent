package com.repoguard.agent.github;

public record GithubReviewCommentDraft(
    Long findingId,
    String path,
    Integer line,
    String body,
    String targetType
) {
}
