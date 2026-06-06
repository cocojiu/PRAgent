package com.repoguard.agent.github;

public record GithubChangedFile(
    String filename,
    String status,
    Integer additions,
    Integer deletions,
    String patch
) {
}
