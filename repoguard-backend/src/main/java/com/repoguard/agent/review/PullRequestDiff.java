package com.repoguard.agent.review;

import java.util.List;

public record PullRequestDiff(
    String owner,
    String repository,
    Integer prNumber,
    String headSha,
    List<PullRequestChangedFile> files,
    PullRequestDiffTruncation truncation
) {

    public PullRequestDiff {
        files = files == null ? List.of() : List.copyOf(files);
        truncation = truncation == null ? PullRequestDiffTruncation.none() : truncation;
    }

    public PullRequestDiff(
        String owner,
        String repository,
        Integer prNumber,
        String headSha,
        List<PullRequestChangedFile> files
    ) {
        this(owner, repository, prNumber, headSha, files, PullRequestDiffTruncation.none());
    }

    public PullRequestDiff(
        String owner,
        String repository,
        Integer prNumber,
        List<PullRequestChangedFile> files
    ) {
        this(owner, repository, prNumber, null, files, PullRequestDiffTruncation.none());
    }

    public boolean truncated() {
        return truncation.truncated();
    }
}
