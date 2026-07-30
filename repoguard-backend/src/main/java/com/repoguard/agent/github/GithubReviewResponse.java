package com.repoguard.agent.github;

import com.fasterxml.jackson.annotation.JsonProperty;

record GithubReviewResponse(
    Long id,
    @JsonProperty("html_url")
    String htmlUrl
) {
}
