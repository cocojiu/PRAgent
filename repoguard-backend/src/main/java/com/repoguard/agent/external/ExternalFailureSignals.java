package com.repoguard.agent.external;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.InterruptedByTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import org.springframework.util.StringUtils;

public final class ExternalFailureSignals {

    private static final String STATUS_MARKER = "status=";

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
            && (ExternalHttpFailureDiagnostics.fromDetail(detail).hasRetryAfter()
                || detail.toLowerCase(Locale.ROOT).contains("retry-after"));
    }

    public static boolean hasRateLimitSignal(String detail) {
        if (!StringUtils.hasText(detail)) {
            return false;
        }
        String lowerDetail = detail.toLowerCase(Locale.ROOT);
        return ExternalHttpFailureDiagnostics.fromDetail(detail).hasRateLimitSignal()
            || lowerDetail.contains("retry-after")
            || lowerDetail.contains("rate limit");
    }

    public static String retryAfterFromDetail(String detail) {
        return ExternalHttpFailureDiagnostics.fromDetail(detail).retryAfter();
    }

    public static ExternalHttpFailureDiagnostics httpDiagnosticsFromDetail(String detail) {
        return ExternalHttpFailureDiagnostics.fromDetail(detail);
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
                || current instanceof HttpTimeoutException
                || current instanceof InterruptedByTimeoutException
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

}
