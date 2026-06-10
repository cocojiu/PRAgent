package com.repoguard.agent.dto;

public record GithubPullRequestOption(
    Integer number,
    String title,
    String branch,
    String commit,
    String headSha,
    String author,
    String url,
    String updatedAt
) {
}
