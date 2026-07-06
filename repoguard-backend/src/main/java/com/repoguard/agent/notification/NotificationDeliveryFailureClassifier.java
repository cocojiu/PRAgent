package com.repoguard.agent.notification;

import java.net.SocketTimeoutException;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

@Component
class NotificationDeliveryFailureClassifier {

    String failureCategory(RuntimeException ex) {
        if (ex instanceof RestClientResponseException responseException) {
            return httpFailureCategory(responseException.getStatusCode().value());
        }
        String detail = normalizedDetail(ex);
        Integer statusCode = statusCodeFromDetail(detail);
        if (statusCode != null) {
            return httpFailureCategory(statusCode);
        }
        if (detail.contains("retryafter=") || detail.contains("retry-after")) {
            return "notification_http_rate_limited";
        }
        if (isTimeout(ex, detail)) {
            return "notification_timeout";
        }
        if (detail.contains("cannot be decrypted")
            || detail.contains("decrypt")
            || detail.contains("webhook url is empty")
            || detail.contains("webhook credentials")) {
            return "notification_channel_config_invalid";
        }
        if (ex instanceof IllegalArgumentException
            || detail.contains("payload")
            || detail.contains("parse")
            || detail.contains("json")) {
            return "notification_payload_invalid";
        }
        return "notification_delivery_failed";
    }

    private String httpFailureCategory(int statusCode) {
        if (statusCode == 429) {
            return "notification_http_rate_limited";
        }
        if (statusCode == 401 || statusCode == 403) {
            return "notification_http_auth_failed";
        }
        if (statusCode == 408) {
            return "notification_timeout";
        }
        if (statusCode >= 500) {
            return "notification_http_service_unavailable";
        }
        if (statusCode >= 400) {
            return "notification_http_request_failed";
        }
        return "notification_delivery_failed";
    }

    private boolean isTimeout(RuntimeException ex, String detail) {
        if (ex instanceof ResourceAccessException && containsTimeout(ex)) {
            return true;
        }
        return detail.contains("timeout")
            || detail.contains("timed out")
            || detail.contains("read timed out")
            || detail.contains("connect timed out");
    }

    private boolean containsTimeout(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            String message = current.getMessage();
            if (StringUtils.hasText(message)) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("timeout") || normalized.contains("timed out")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private Integer statusCodeFromDetail(String detail) {
        int marker = detail.indexOf("status=");
        if (marker < 0) {
            return null;
        }
        int start = marker + "status=".length();
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

    private String normalizedDetail(RuntimeException ex) {
        String message = ex == null ? "" : ex.getMessage();
        return message == null ? "" : message.toLowerCase(Locale.ROOT);
    }
}
