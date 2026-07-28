package com.repoguard.agent.github;

import java.util.List;

public record GithubPullRequestDiff(
    String owner,
    String repository,
    Integer prNumber,
    String headSha,
    List<GithubChangedFile> files
) {

    public GithubPullRequestDiff(
        String owner,
        String repository,
        Integer prNumber,
        List<GithubChangedFile> files
    ) {
        this(owner, repository, prNumber, null, files);
    }
}
