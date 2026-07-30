package com.repoguard.agent.github;

import com.fasterxml.jackson.annotation.JsonProperty;

record GithubReviewCommentDetail(
    Long id,
    @JsonProperty("html_url")
    String htmlUrl,
    String path,
    Integer line,
    @JsonProperty("original_line")
    Integer originalLine,
    String body
) {
}
