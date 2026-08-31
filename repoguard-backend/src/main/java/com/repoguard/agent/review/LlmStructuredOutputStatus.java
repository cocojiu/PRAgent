package com.repoguard.agent.review;

/**
 * Per-call structured-output outcome. It is deliberately kept in the call result so a chunked
 * review can retain the fact that one provider request had to fall back to the legacy parser path.
 */
public enum LlmStructuredOutputStatus {
    NOT_REQUESTED("not_requested"),
    REQUESTED("requested"),
    FALLBACK("fallback"),
    FAILED("failed");

    private final String code;

    LlmStructuredOutputStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
