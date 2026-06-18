package com.repoguard.agent.notification;

import java.util.Locale;

public enum NotificationDeliveryStatus {

    SUCCESS("SUCCESS"),
    FAILED("FAILED"),
    UNKNOWN("UNKNOWN");

    private final String code;

    NotificationDeliveryStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static NotificationDeliveryStatus from(String status) {
        if (status == null || status.isBlank()) {
            return UNKNOWN;
        }
        String normalizedStatus = status.trim().toUpperCase(Locale.ROOT);
        for (NotificationDeliveryStatus deliveryStatus : values()) {
            if (deliveryStatus.code.equals(normalizedStatus)) {
                return deliveryStatus;
            }
        }
        return UNKNOWN;
    }
}
