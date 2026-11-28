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

    public PullRequestDiff(String owner, String repository, Integer prNumber, String headSha,
                          List<PullRequestChangedFile> files) {
        this(owner, repository, prNumber, headSha, files, PullRequestDiffTruncation.none());
    }

    public PullRequestDiff(String owner, String repository, Integer prNumber, List<PullRequestChangedFile> files) {
        this(owner, repository, prNumber, null, files, PullRequestDiffTruncation.none());
    }

    public boolean truncated() {
        return truncation.truncated();
    }

    public boolean hasWrapperPinChange() {
        return !removedWrapperPins().isEmpty();
    }

    List<String> removedWrapperPins() {
        return files.stream()
            .filter(file -> file.filename() != null && (file.filename().endsWith("maven-wrapper.properties")
                || file.filename().endsWith("gradle-wrapper.properties")) && file.patch() != null)
            .flatMap(file -> file.patch().lines())
            .filter(line -> line.startsWith("-distributionUrl=") || line.startsWith("-distributionSha256Sum="))
            .map(line -> line.substring(1)).filter(line -> line.length() <= 1024).distinct().limit(8).toList();
    }
}
