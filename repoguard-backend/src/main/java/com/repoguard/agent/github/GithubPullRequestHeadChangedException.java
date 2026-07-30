package com.repoguard.agent.github;

public final class GithubPullRequestHeadChangedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String expectedHeadSha;
    private final String currentHeadSha;

    public GithubPullRequestHeadChangedException(String expectedHeadSha, String currentHeadSha) {
        super(
            "GitHub pull request head changed: expected="
                + display(expectedHeadSha)
                + " current="
                + display(currentHeadSha)
        );
        this.expectedHeadSha = expectedHeadSha;
        this.currentHeadSha = currentHeadSha;
    }

    public String expectedHeadSha() {
        return expectedHeadSha;
    }

    public String currentHeadSha() {
        return currentHeadSha;
    }

    private static String display(String value) {
        return value == null || value.isBlank() ? "unavailable" : value.trim();
    }
}
