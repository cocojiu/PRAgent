package com.repoguard.agent.notification;

import java.util.Locale;

public enum NotificationEventType {

    REVIEW_COMPLETED("REVIEW_COMPLETED"),
    REVIEW_FAILED("REVIEW_FAILED"),
    HUMAN_REVIEW_REQUIRED("HUMAN_REVIEW_REQUIRED"),
    GITHUB_COMMENT_PUBLISHED("GITHUB_COMMENT_PUBLISHED"),
    MODEL_RELEASE_ALERT("MODEL_RELEASE_ALERT"),
    UNKNOWN("UNKNOWN");

    private final String code;

    NotificationEventType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static NotificationEventType from(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return UNKNOWN;
        }
        String normalizedType = eventType.trim().toUpperCase(Locale.ROOT);
        for (NotificationEventType type : values()) {
            if (type.code.equals(normalizedType)) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
