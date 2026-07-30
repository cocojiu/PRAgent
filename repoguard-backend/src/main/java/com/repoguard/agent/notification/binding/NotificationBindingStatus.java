package com.repoguard.agent.notification.binding;

import java.util.Locale;

public enum NotificationBindingStatus {

    CONFIGURED("CONFIGURED"),
    CONNECTED("CONNECTED"),
    FAILED("FAILED"),
    DELETED("DELETED"),
    UNKNOWN("UNKNOWN");

    private final String code;

    NotificationBindingStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static NotificationBindingStatus from(String status) {
        if (status == null || status.isBlank()) {
            return UNKNOWN;
        }
        String normalizedStatus = status.trim().toUpperCase(Locale.ROOT);
        for (NotificationBindingStatus bindingStatus : values()) {
            if (bindingStatus.code.equals(normalizedStatus)) {
                return bindingStatus;
            }
        }
        return UNKNOWN;
    }
}
