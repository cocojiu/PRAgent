package com.repoguard.agent.notification;

import java.util.Locale;

public enum NotificationEventStatus {

    PENDING("PENDING"),
    PUBLISHING("PUBLISHING"),
    PUBLISHED("PUBLISHED"),
    DELIVERING("DELIVERING"),
    DELIVERED("DELIVERED"),
    PUBLISH_FAILED("PUBLISH_FAILED"),
    DELIVERY_FAILED("DELIVERY_FAILED"),
    DEAD("DEAD"),
    UNKNOWN("UNKNOWN");

    private final String code;

    NotificationEventStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static NotificationEventStatus from(String status) {
        if (status == null || status.isBlank()) {
            return UNKNOWN;
        }
        String normalizedStatus = status.trim().toUpperCase(Locale.ROOT);
        for (NotificationEventStatus eventStatus : values()) {
            if (eventStatus.code.equals(normalizedStatus)) {
                return eventStatus;
            }
        }
        return UNKNOWN;
    }
}
