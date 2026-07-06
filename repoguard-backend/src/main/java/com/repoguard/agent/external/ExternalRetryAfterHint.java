package com.repoguard.agent.external;

import org.springframework.util.StringUtils;

public final class ExternalRetryAfterHint {

    private static final String MARKER = "retryAfter=";
    private static final String RESPONSE_BODY_MARKER = " responseBody=";

    private ExternalRetryAfterHint() {
    }

    public static String suggestionSuffix(String detail) {
        String retryAfter = retryAfter(detail);
        if (!StringUtils.hasText(retryAfter)) {
            return "";
        }
        return "建议等待 " + retryAfter + " 后再重试。";
    }

    static String retryAfter(String detail) {
        if (!StringUtils.hasText(detail)) {
            return "";
        }
        int markerIndex = detail.indexOf(MARKER);
        if (markerIndex < 0) {
            return "";
        }
        int valueStart = markerIndex + MARKER.length();
        int valueEnd = detail.indexOf(RESPONSE_BODY_MARKER, valueStart);
        if (valueEnd < 0) {
            valueEnd = detail.length();
        }
        String value = detail.substring(valueStart, valueEnd)
            .replaceAll("[^A-Za-z0-9,: GMT+-]", "")
            .replaceAll("\\s+", " ")
            .trim();
        return value.length() > 64 ? value.substring(0, 64) : value;
    }
}
