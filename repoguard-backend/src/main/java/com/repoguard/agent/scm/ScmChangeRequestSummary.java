package com.repoguard.agent.scm;

public record ScmChangeRequestSummary(
    String provider,
    String namespace,
    String repository,
    Integer number,
    String title,
    String branch,
    String commit,
    String author,
    String url,
    String updatedAt
) {
}
