package com.repoguard.agent.external;

import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import org.springframework.util.StringUtils;

public final class ExternalFailureSignals {

    private static final String STATUS_MARKER = "status=";
    private static final String RETRY_AFTER_MARKER = "retryAfter=";
    private static final String RESPONSE_BODY_MARKER = " responseBody=";
    private static final String RATE_LIMIT_MARKER_PREFIX = " rateLimit";

    private ExternalFailureSignals() {
    }

    public static String normalizedDetail(RuntimeException ex) {
        String message = ex == null ? "" : ex.getMessage();
        return message == null ? "" : message.toLowerCase(Locale.ROOT);
    }

    public static Integer statusCodeFromDetail(String detail) {
        if (!StringUtils.hasText(detail)) {
            return null;
        }
        int marker = detail.indexOf(STATUS_MARKER);
        if (marker < 0) {
            return null;
        }
        int start = marker + STATUS_MARKER.length();
        int end = start;
        while (end < detail.length() && Character.isDigit(detail.charAt(end))) {
            end++;
        }
        if (end == start) {
            return null;
        }
        try {
            return Integer.parseInt(detail.substring(start, end));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static boolean hasRetryAfterSignal(String detail) {
        return StringUtils.hasText(detail)
            && (detail.contains("retryafter=") || detail.contains("retry-after"));
    }

    public static String retryAfterFromDetail(String detail) {
        if (!StringUtils.hasText(detail)) {
            return "";
        }
        int markerIndex = detail.indexOf(RETRY_AFTER_MARKER);
        if (markerIndex < 0) {
            return "";
        }
        int valueStart = markerIndex + RETRY_AFTER_MARKER.length();
        int valueEnd = earliestMarkerIndex(detail, valueStart);
        return detail.substring(valueStart, valueEnd);
    }

    public static boolean hasTimeoutSignal(
        Throwable throwable,
        String detail,
        boolean includeGenericTimeoutException
    ) {
        return hasTimeoutCause(throwable, includeGenericTimeoutException) || hasTimeoutText(detail);
    }

    private static boolean hasTimeoutCause(Throwable throwable, boolean includeGenericTimeoutException) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                || (includeGenericTimeoutException && current instanceof TimeoutException)) {
                return true;
            }
            if (hasTimeoutText(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean hasTimeoutText(String detail) {
        return StringUtils.hasText(detail)
            && detail.toLowerCase(Locale.ROOT).matches(".*(timeout|timed out|read timed out|connect timed out).*");
    }

    private static int earliestMarkerIndex(String detail, int start) {
        int end = detail.length();
        end = nearest(detail, RESPONSE_BODY_MARKER, start, end);
        end = nearest(detail, RATE_LIMIT_MARKER_PREFIX, start, end);
        return end;
    }

    private static int nearest(String detail, String marker, int start, int currentEnd) {
        int index = detail.indexOf(marker, start);
        return index < 0 ? currentEnd : Math.min(currentEnd, index);
    }
}
