package com.repoguard.agent.review;

import java.util.Locale;

public enum LlmParseStatus {

    PARTIAL_FALLBACK("partial_fallback");

    private final String code;

    LlmParseStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public boolean is(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        return code.equals(status.trim().toLowerCase(Locale.ROOT));
    }
}
