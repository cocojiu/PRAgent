package com.repoguard.agent.github;

import com.repoguard.agent.review.PullRequestChangedFile;
import com.repoguard.agent.review.PullRequestDiffTruncation;
import java.util.List;

public record GithubChangedFileFetch(
    List<PullRequestChangedFile> files,
    PullRequestDiffTruncation truncation
) {

    public GithubChangedFileFetch {
        files = files == null ? List.of() : List.copyOf(files);
        truncation = truncation == null ? PullRequestDiffTruncation.none() : truncation;
    }
}
