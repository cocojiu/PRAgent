package com.repoguard.agent.github;

public enum GithubCommentPublicationBatchStatus {
    QUEUED("queued"),
    RUNNING("running"),
    EMPTY("empty"),
    COMPLETED("completed"),
    SKIPPED("skipped"),
    PARTIAL_FAILED("partial_failed"),
    FAILED("failed");

    private final String code;

    GithubCommentPublicationBatchStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
