package com.repoguard.agent.review;

import java.util.Locale;

public enum HumanReviewStatus {

    PENDING("PENDING"),
    APPROVED("APPROVED"),
    CHANGES_REQUESTED("CHANGES_REQUESTED"),
    REJECTED("REJECTED"),
    NOT_REQUIRED("NOT_REQUIRED"),
    UNKNOWN("UNKNOWN");

    private final String code;

    HumanReviewStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public boolean allowsGithubCommentPublish() {
        return this == APPROVED || this == CHANGES_REQUESTED;
    }

    public static HumanReviewStatus from(String status) {
        if (status == null || status.isBlank()) {
            return UNKNOWN;
        }
        String normalizedStatus = status.trim().toUpperCase(Locale.ROOT);
        for (HumanReviewStatus humanReviewStatus : values()) {
            if (humanReviewStatus.code.equals(normalizedStatus)) {
                return humanReviewStatus;
            }
        }
        return UNKNOWN;
    }

    public static HumanReviewStatus fromAction(String action) {
        if (action == null || action.isBlank()) {
            return UNKNOWN;
        }
        return switch (action.trim().toUpperCase(Locale.ROOT)) {
            case "APPROVE" -> APPROVED;
            case "CHANGES_REQUESTED" -> CHANGES_REQUESTED;
            case "REJECT" -> REJECTED;
            default -> UNKNOWN;
        };
    }

    public static HumanReviewStatus defaultForRequired(boolean humanReviewRequired) {
        return humanReviewRequired ? PENDING : NOT_REQUIRED;
    }
}
