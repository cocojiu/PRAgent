package com.repoguard.agent.github;

import java.util.List;

public record GithubPullRequestDiff(
    String owner,
    String repository,
    Integer prNumber,
    String headSha,
    List<GithubChangedFile> files,
    GithubDiffTruncation truncation
) {

    public GithubPullRequestDiff {
        files = files == null ? List.of() : List.copyOf(files);
        truncation = truncation == null ? GithubDiffTruncation.none() : truncation;
    }

    public GithubPullRequestDiff(
        String owner,
        String repository,
        Integer prNumber,
        String headSha,
        List<GithubChangedFile> files
    ) {
        this(owner, repository, prNumber, headSha, files, GithubDiffTruncation.none());
    }

    public GithubPullRequestDiff(
        String owner,
        String repository,
        Integer prNumber,
        List<GithubChangedFile> files
    ) {
        this(owner, repository, prNumber, null, files, GithubDiffTruncation.none());
    }

    public boolean truncated() {
        return truncation.truncated();
    }
}
