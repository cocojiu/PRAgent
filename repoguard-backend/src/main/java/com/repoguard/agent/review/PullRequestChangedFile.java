package com.repoguard.agent.review;

public record PullRequestChangedFile(
    String filename,
    String status,
    Integer additions,
    Integer deletions,
    String patch
) {
}
