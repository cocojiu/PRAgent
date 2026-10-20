package com.repoguard.agent.github;

import com.repoguard.agent.external.ExternalCallException;

public enum GithubCommentPublicationStatus {
    PUBLISHED("published"),
    DOWNGRADED_TO_PR_COMMENT("downgraded_to_pr_comment"),
    FAILED("failed"),
    CLOSED("closed"),
    OUTCOME_UNKNOWN("outcome_unknown"),
    ALREADY_PUBLISHED("already_published"),
    SKIPPED("skipped");

    public static final String VERIFICATION_MESSAGE =
        "GitHub 回写结果待核验：请求可能已成功，请先核对目标 PR 的评论和 Review，核验前不会再次发布。";

    public static String failureCode(RuntimeException failure) {
        return failure instanceof ExternalCallException external
            && "github_write_outcome_unknown".equals(external.getCategory())
            ? OUTCOME_UNKNOWN.code() : FAILED.code();
    }

    private final String code;

    GithubCommentPublicationStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
