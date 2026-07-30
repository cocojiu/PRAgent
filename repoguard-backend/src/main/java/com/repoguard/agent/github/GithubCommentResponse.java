package com.repoguard.agent.github;

import com.fasterxml.jackson.annotation.JsonProperty;

record GithubCommentResponse(
    Long id,
    @JsonProperty("html_url")
    String htmlUrl
) {
}
