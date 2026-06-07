package com.repoguard.agent.dto;

public record GithubPullRequestOption(
    Integer number,
    String title,
    String branch,
    String commit,
    String author,
    String url,
    String updatedAt
) {
}
