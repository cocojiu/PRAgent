package com.repoguard.agent.dto;

public record GithubCommentPublishItem(
    Long findingId,
    String file,
    Integer line,
    Boolean success,
    String status,
    String message,
    String url,
    Long githubCommentId,
    String publishedAt
) {
}
