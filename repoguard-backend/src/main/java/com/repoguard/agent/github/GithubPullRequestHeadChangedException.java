package com.repoguard.agent.github;

import java.time.LocalDateTime;

public final class GithubPullRequestHeadChangedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String expectedHeadSha;
    private final String currentHeadSha;
    private final LocalDateTime currentHeadUpdatedAt;

    public GithubPullRequestHeadChangedException(String expectedHeadSha, String currentHeadSha) {
        this(expectedHeadSha, currentHeadSha, null);
    }

    public GithubPullRequestHeadChangedException(
        String expectedHeadSha,
        String currentHeadSha,
        LocalDateTime currentHeadUpdatedAt
    ) {
        super(
            "GitHub pull request head changed: expected="
                + display(expectedHeadSha)
                + " current="
                + display(currentHeadSha)
        );
        this.expectedHeadSha = expectedHeadSha;
        this.currentHeadSha = currentHeadSha;
        this.currentHeadUpdatedAt = currentHeadUpdatedAt;
    }

    public String expectedHeadSha() {
        return expectedHeadSha;
    }

    public String currentHeadSha() {
        return currentHeadSha;
    }

    public LocalDateTime currentHeadUpdatedAt() {
        return currentHeadUpdatedAt;
    }

    private static String display(String value) {
        return value == null || value.isBlank() ? "unavailable" : value.trim();
    }
}
