package com.repoguard.agent.review;

import java.util.Locale;

public enum ReviewTaskStatus {

    QUEUED("QUEUED"),
    REVIEWING("REVIEWING"),
    COMPLETED("COMPLETED"),
    FAILED("FAILED"),
    PUBLISH_FAILED("PUBLISH_FAILED"),
    PENDING_HUMAN_REVIEW("PENDING_HUMAN_REVIEW"),
    APPROVED("APPROVED"),
    CHANGES_REQUESTED("CHANGES_REQUESTED"),
    REJECTED("REJECTED"),
    UNKNOWN("UNKNOWN");

    private final String code;

    ReviewTaskStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static ReviewTaskStatus from(String status) {
        if (status == null || status.isBlank()) {
            return UNKNOWN;
        }
        String normalizedStatus = status.trim().toUpperCase(Locale.ROOT);
        for (ReviewTaskStatus taskStatus : values()) {
            if (taskStatus.code.equals(normalizedStatus)) {
                return taskStatus;
            }
        }
        return UNKNOWN;
    }
}
