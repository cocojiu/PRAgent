package com.repoguard.agent.github;

public record GithubRepositoryRef(
    String owner,
    String repository
) {
}
