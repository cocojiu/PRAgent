package com.repoguard.agent.timeline;

import java.util.Locale;

public enum ReviewTimelineStatus {

    DONE("DONE", "done"),
    CURRENT("CURRENT", "current"),
    FAILED("FAILED", "done"),
    UNKNOWN("UNKNOWN", "pending");

    private final String code;
    private final String displayStatus;

    ReviewTimelineStatus(String code, String displayStatus) {
        this.code = code;
        this.displayStatus = displayStatus;
    }

    public String code() {
        return code;
    }

    public String displayStatus() {
        return displayStatus;
    }

    public static ReviewTimelineStatus from(String status) {
        if (status == null || status.isBlank()) {
            return UNKNOWN;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        for (ReviewTimelineStatus timelineStatus : values()) {
            if (timelineStatus.code.equals(normalized)) {
                return timelineStatus;
            }
        }
        return UNKNOWN;
    }
}
