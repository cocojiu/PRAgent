package com.repoguard.agent.github;

import org.springframework.util.StringUtils;

public enum GithubCommentTargetType {
    PULL_REQUEST("pull_request"),
    LINE("line");

    private final String code;

    GithubCommentTargetType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public boolean isPullRequest() {
        return this == PULL_REQUEST;
    }

    public boolean isLine() {
        return this == LINE;
    }

    public static GithubCommentTargetType from(String value) {
        if (!StringUtils.hasText(value)) {
            return PULL_REQUEST;
        }
        String normalized = value.trim();
        for (GithubCommentTargetType targetType : values()) {
            if (targetType.code.equalsIgnoreCase(normalized)) {
                return targetType;
            }
        }
        return PULL_REQUEST;
    }
}
