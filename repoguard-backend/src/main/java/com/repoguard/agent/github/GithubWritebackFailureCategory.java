package com.repoguard.agent.github;

public enum GithubWritebackFailureCategory {
    TOKEN_MISSING("github_token_missing"),
    TOKEN_INVALID("github_token_invalid"),
    PERMISSION_DENIED("github_permission_denied"),
    TARGET_NOT_FOUND("github_target_not_found"),
    RATE_LIMITED("github_rate_limited"),
    WRITEBACK_TIMEOUT("github_writeback_timeout"),
    SERVICE_UNAVAILABLE("github_service_unavailable"),
    REPOSITORY_NOT_CONFIGURED("github_repository_not_configured"),
    COMMENT_POSITION_INVALID("github_comment_position_invalid"),
    WRITEBACK_FAILED("github_writeback_failed");

    private final String code;

    GithubWritebackFailureCategory(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public String categoryMarker() {
        return "category=" + code;
    }
}
