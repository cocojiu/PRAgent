package com.repoguard.agent.review;

import java.util.Locale;

public enum AssessmentStatus {
    COMPLETE,
    PARTIAL,
    FAILED,
    SUPERSEDED;

    public static AssessmentStatus forCompletedReview(ReviewResult result) {
        if (result == null) {
            return FAILED;
        }
        String parseStatus = normalize(result.llmParseStatus());
        String llmStatus = normalize(result.llmStatus());
        if ("PARTIAL_FALLBACK".equals(parseStatus)
            || "FALLBACK".equals(parseStatus)
            || "FALLBACK".equals(llmStatus)) {
            return PARTIAL;
        }
        return COMPLETE;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
