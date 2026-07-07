package com.repoguard.agent.external;

import java.util.List;
import java.util.Locale;
import org.springframework.util.StringUtils;

public record ExternalHttpFailureDiagnostics(
    String retryAfter,
    String rateLimitLimit,
    String rateLimitRemaining,
    String rateLimitUsed,
    String rateLimitReset,
    String rateLimitResource
) {

    private static final int MAX_VALUE_LENGTH = 64;
    private static final List<String> DETAIL_MARKERS = List.of(
        "retryafter=",
        "ratelimitlimit=",
        "ratelimitremaining=",
        "ratelimitused=",
        "ratelimitreset=",
        "ratelimitresource=",
        "responsebody="
    );

    public ExternalHttpFailureDiagnostics {
        retryAfter = clean(retryAfter);
        rateLimitLimit = clean(rateLimitLimit);
        rateLimitRemaining = clean(rateLimitRemaining);
        rateLimitUsed = clean(rateLimitUsed);
        rateLimitReset = clean(rateLimitReset);
        rateLimitResource = clean(rateLimitResource);
    }

    public static ExternalHttpFailureDiagnostics empty() {
        return new ExternalHttpFailureDiagnostics("", "", "", "", "", "");
    }

    public static ExternalHttpFailureDiagnostics fromDetail(String detail) {
        if (!StringUtils.hasText(detail)) {
            return empty();
        }
        String normalized = detail.trim();
        return new ExternalHttpFailureDiagnostics(
            value(normalized, "retryAfter"),
            value(normalized, "rateLimitLimit"),
            value(normalized, "rateLimitRemaining"),
            value(normalized, "rateLimitUsed"),
            value(normalized, "rateLimitReset"),
            value(normalized, "rateLimitResource")
        );
    }

    public boolean hasRetryAfter() {
        return StringUtils.hasText(retryAfter);
    }

    public boolean hasRateLimitDiagnostics() {
        return StringUtils.hasText(rateLimitLimit)
            || StringUtils.hasText(rateLimitRemaining)
            || StringUtils.hasText(rateLimitUsed)
            || StringUtils.hasText(rateLimitReset)
            || StringUtils.hasText(rateLimitResource);
    }

    public boolean hasRateLimitSignal() {
        return hasRetryAfter() || hasRateLimitDiagnostics();
    }

    private static String value(String detail, String fieldName) {
        String lowerDetail = detail.toLowerCase(Locale.ROOT);
        String marker = fieldName.toLowerCase(Locale.ROOT) + "=";
        int markerIndex = lowerDetail.indexOf(marker);
        if (markerIndex < 0) {
            return "";
        }
        int valueStart = markerIndex + marker.length();
        int valueEnd = detail.length();
        for (String nextMarker : DETAIL_MARKERS) {
            int index = lowerDetail.indexOf(nextMarker, valueStart);
            if (index >= 0) {
                valueEnd = Math.min(valueEnd, index);
            }
        }
        return detail.substring(valueStart, valueEnd).trim();
    }

    private static String clean(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String safe = value
            .replaceAll("[^A-Za-z0-9,: GMT+_.-]", "")
            .replaceAll("\\s+", " ")
            .trim();
        return safe.length() > MAX_VALUE_LENGTH ? safe.substring(0, MAX_VALUE_LENGTH) : safe;
    }
}
