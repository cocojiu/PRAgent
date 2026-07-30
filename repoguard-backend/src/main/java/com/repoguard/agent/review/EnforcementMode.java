package com.repoguard.agent.review;

import java.util.Locale;

public enum EnforcementMode {
    OBSERVE,
    COMMENT,
    BLOCK;

    public static EnforcementMode from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Review rule enforcementMode must not be blank");
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported review rule enforcementMode: " + value, ex);
        }
    }
}
