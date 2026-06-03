package com.repoguard.agent.common;

import java.time.OffsetDateTime;

public record ApiResponse<T>(
    boolean success,
    String code,
    String message,
    T data,
    OffsetDateTime timestamp
) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, ErrorCode.OK.code(), "OK", data, OffsetDateTime.now());
    }

    public static ApiResponse<Void> error(ErrorCode code, String message) {
        return new ApiResponse<>(false, code.code(), message, null, OffsetDateTime.now());
    }
}
