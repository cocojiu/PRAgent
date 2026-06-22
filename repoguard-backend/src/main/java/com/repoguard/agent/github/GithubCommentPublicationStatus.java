package com.repoguard.agent.github;

public enum GithubCommentPublicationStatus {
    PUBLISHED("published"),
    DOWNGRADED_TO_PR_COMMENT("downgraded_to_pr_comment"),
    FAILED("failed"),
    ALREADY_PUBLISHED("already_published"),
    SKIPPED("skipped");

    private final String code;

    GithubCommentPublicationStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
