package com.repoguard.agent.github;

public record GithubPullRequestSummary(
    String owner,
    String repository,
    Integer number,
    String title,
    String branch,
    String commit,
    String author,
    String url,
    String updatedAt
) {
}
