package com.repoguard.agent.review;

import java.util.Locale;

public enum LlmParseStatus {

    PARSED("parsed"),
    FALLBACK("fallback"),
    PARTIAL_FALLBACK("partial_fallback"),
    UNKNOWN("unknown");

    private final String code;

    LlmParseStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public boolean is(String status) {
        return this == from(status);
    }

    public static LlmParseStatus from(String status) {
        if (status == null || status.isBlank()) {
            return UNKNOWN;
        }
        String normalizedStatus = status.trim().toLowerCase(Locale.ROOT);
        for (LlmParseStatus parseStatus : values()) {
            if (parseStatus.code.equals(normalizedStatus)) {
                return parseStatus;
            }
        }
        return UNKNOWN;
    }

    public static String dtoCodeOrNull(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return from(status).code();
    }
}
