package com.repoguard.agent.github;

import java.util.List;

public record GithubPullRequestDiff(
    String owner,
    String repository,
    Integer prNumber,
    List<GithubChangedFile> files
) {
}
