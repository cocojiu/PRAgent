package com.repoguard.agent.review;

import java.util.Locale;
import org.springframework.util.StringUtils;

public enum ReviewTaskSource {
    MANUAL_INPUT("MANUAL_INPUT"),
    GITHUB_PR_PICKER("GITHUB_PR_PICKER"),
    EXISTING_REUSED("EXISTING_REUSED");

    private final String code;

    ReviewTaskSource(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public String dtoCode() {
        return code.toLowerCase(Locale.ROOT);
    }

    public static ReviewTaskSource creationSource(String value) {
        ReviewTaskSource source = from(value);
        return GITHUB_PR_PICKER == source ? GITHUB_PR_PICKER : MANUAL_INPUT;
    }

    public static ReviewTaskSource from(String value) {
        if (!StringUtils.hasText(value)) {
            return MANUAL_INPUT;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (ReviewTaskSource source : values()) {
            if (source.code.equals(normalized)) {
                return source;
            }
        }
        return MANUAL_INPUT;
    }

    public static String storedCodeOrDefault(String value) {
        return StringUtils.hasText(value) ? value : MANUAL_INPUT.code;
    }

    public static String dtoCodeOrDefault(String value) {
        return storedCodeOrDefault(value).toLowerCase(Locale.ROOT);
    }
}
