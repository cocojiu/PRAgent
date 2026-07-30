package com.repoguard.agent.common;

public enum ErrorCode {
    OK("OK"),
    BAD_REQUEST("BAD_REQUEST"),
    PAYLOAD_TOO_LARGE("PAYLOAD_TOO_LARGE"),
    TOO_MANY_REQUESTS("TOO_MANY_REQUESTS"),
    CONFLICT("CONFLICT"),
    UNAUTHORIZED("UNAUTHORIZED"),
    FORBIDDEN("FORBIDDEN"),
    TASK_NOT_FOUND("TASK_NOT_FOUND"),
    INTERNAL_ERROR("INTERNAL_ERROR");

    private final String code;

    ErrorCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
