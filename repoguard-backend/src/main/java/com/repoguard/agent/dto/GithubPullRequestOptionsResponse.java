package com.repoguard.agent.dto;

import java.util.List;

public record GithubPullRequestOptionsResponse(
    String organization,
    String repository,
    List<GithubPullRequestOption> items
) {
}
