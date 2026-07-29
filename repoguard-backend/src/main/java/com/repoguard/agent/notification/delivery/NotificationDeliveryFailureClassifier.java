package com.repoguard.agent.notification.delivery;

import com.repoguard.agent.external.ExternalFailureSignals;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class NotificationDeliveryFailureClassifier {

    public String failureCategory(RuntimeException ex) {
        if (ex instanceof RestClientResponseException responseException) {
            return httpFailureCategory(responseException.getStatusCode().value());
        }
        String detail = ExternalFailureSignals.normalizedDetail(ex);
        Integer statusCode = ExternalFailureSignals.statusCodeFromDetail(detail);
        if (statusCode != null) {
            return httpFailureCategory(statusCode);
        }
        if (ExternalFailureSignals.hasRateLimitSignal(detail)) {
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
        if (ex instanceof ResourceAccessException && ExternalFailureSignals.hasTimeoutSignal(ex, detail, false)) {
            return true;
        }
        return ExternalFailureSignals.hasTimeoutSignal(null, detail, false);
    }
}
