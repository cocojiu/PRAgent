package com.repoguard.agent.github;

import java.util.List;

public record GithubChangedFileFetch(
    List<GithubChangedFile> files,
    GithubDiffTruncation truncation
) {

    public GithubChangedFileFetch {
        files = files == null ? List.of() : List.copyOf(files);
        truncation = truncation == null ? GithubDiffTruncation.none() : truncation;
    }
}
