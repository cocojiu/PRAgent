package com.repoguard.agent.review;

import java.util.Locale;

public enum LlmStatus {

    PENDING("PENDING"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED"),
    FALLBACK("FALLBACK"),
    UNKNOWN("UNKNOWN");

    private final String code;

    LlmStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static LlmStatus from(String status) {
        if (status == null || status.isBlank()) {
            return UNKNOWN;
        }
        String normalizedStatus = status.trim().toUpperCase(Locale.ROOT);
        for (LlmStatus llmStatus : values()) {
            if (llmStatus.code.equals(normalizedStatus)) {
                return llmStatus;
            }
        }
        return UNKNOWN;
    }
}
