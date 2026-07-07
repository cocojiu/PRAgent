package com.repoguard.agent.review;

import com.repoguard.agent.entity.ReviewFinding;
import java.util.Locale;
import org.springframework.util.StringUtils;

public enum FindingFeedbackStatus {
    UNREVIEWED("UNREVIEWED", true, true),
    VALID("VALID", true, true),
    FALSE_POSITIVE("FALSE_POSITIVE", false, true),
    FIXED("FIXED", false, true),
    IGNORED("IGNORED", false, true),
    UNKNOWN("UNKNOWN", false, false);

    private final String code;
    private final boolean commentable;
    private final boolean requestAllowed;

    FindingFeedbackStatus(String code, boolean commentable, boolean requestAllowed) {
        this.code = code;
        this.commentable = commentable;
        this.requestAllowed = requestAllowed;
    }

    public String code() {
        return code;
    }

    public String dtoCode() {
        return code.toLowerCase(Locale.ROOT);
    }

    public boolean commentable() {
        return commentable;
    }

    public boolean unreviewed() {
        return this == UNREVIEWED;
    }

    public static FindingFeedbackStatus from(String status) {
        if (!StringUtils.hasText(status)) {
            return UNREVIEWED;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        for (FindingFeedbackStatus feedbackStatus : values()) {
            if (feedbackStatus.requestAllowed && feedbackStatus.code.equals(normalized)) {
                return feedbackStatus;
            }
        }
        throw new IllegalArgumentException("Unsupported finding feedback status: " + status);
    }

    public static FindingFeedbackStatus fromFinding(ReviewFinding finding) {
        return fromStored(finding == null ? null : finding.getFeedbackStatus());
    }

    public static FindingFeedbackStatus fromStored(String status) {
        if (!StringUtils.hasText(status)) {
            return UNREVIEWED;
        }
        String normalized = status.trim().toUpperCase(Locale.ROOT);
        for (FindingFeedbackStatus feedbackStatus : values()) {
            if (feedbackStatus.code.equals(normalized)) {
                return feedbackStatus;
            }
        }
        return UNKNOWN;
    }

    public static String queryCode(String status) {
        if (!StringUtils.hasText(status)) {
            return UNREVIEWED.code;
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }
}
