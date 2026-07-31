package com.repoguard.agent.review;

import java.nio.charset.StandardCharsets;

public record ChangedFileContext(
    String filePath,
    String headSha,
    String content,
    Status status,
    String reason
) {

    public enum Status {
        NOT_REQUESTED,
        AVAILABLE,
        NOT_REQUIRED,
        EXCLUDED,
        UNAVAILABLE,
        BUDGET_EXCEEDED,
        TOO_LARGE,
        BINARY,
        DELETED
    }

    public ChangedFileContext {
        content = content == null ? "" : content;
        status = status == null ? Status.NOT_REQUESTED : status;
        reason = reason == null ? status.name().toLowerCase() : reason;
    }

    public static ChangedFileContext notRequested(String filePath) {
        return new ChangedFileContext(filePath, null, "", Status.NOT_REQUESTED, "legacy_or_offline_input");
    }

    public static ChangedFileContext available(String filePath, String headSha, String content) {
        return new ChangedFileContext(filePath, headSha, content, Status.AVAILABLE, "fetched_at_exact_head");
    }

    public static ChangedFileContext status(
        String filePath,
        String headSha,
        Status status,
        String reason
    ) {
        return new ChangedFileContext(filePath, headSha, "", status, reason);
    }

    public boolean available() {
        return status == Status.AVAILABLE;
    }

    public boolean missingAfterRequest() {
        return status == Status.UNAVAILABLE
            || status == Status.BUDGET_EXCEEDED
            || status == Status.TOO_LARGE
            || status == Status.BINARY;
    }

    public int contentBytes() {
        return content.getBytes(StandardCharsets.UTF_8).length;
    }
}
