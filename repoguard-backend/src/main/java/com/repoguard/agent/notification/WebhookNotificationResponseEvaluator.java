package com.repoguard.agent.notification;

import com.repoguard.agent.common.SensitiveTextSanitizer;
import com.repoguard.agent.external.ExternalRetryAfterHint;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientResponseException;

@Component
class WebhookNotificationResponseEvaluator {

    private static final int MAX_RESPONSE_LENGTH = 512;

    private final NotificationTextLimiter textLimiter;

    WebhookNotificationResponseEvaluator(NotificationTextLimiter textLimiter) {
        this.textLimiter = Objects.requireNonNull(textLimiter, "textLimiter");
    }

    NotificationSendResult evaluate(Object response) {
        String responseText = response == null ? "" : safeMessage(response.toString());
        if (isSuccessResponse(responseText)) {
            return NotificationSendResult.success(null, responseText);
        }
        return NotificationSendResult.failed(null, responseText);
    }

    NotificationSendResult failure(RuntimeException ex) {
        if (ex instanceof RestClientResponseException responseException) {
            return NotificationSendResult.failed(null, safeMessage(httpFailureMessage(responseException)));
        }
        return NotificationSendResult.failed(null, safeMessage(ex.getMessage()));
    }

    private boolean isSuccessResponse(String responseText) {
        String normalized = responseText == null ? "" : responseText.toLowerCase();
        return normalized.contains("errcode=0")
            || normalized.contains("\"errcode\":0")
            || normalized.contains("errmsg=ok")
            || normalized.contains("\"errmsg\":\"ok\"");
    }

    private String safeMessage(String value) {
        return textLimiter.limit(SensitiveTextSanitizer.sanitize(value), MAX_RESPONSE_LENGTH);
    }

    private String httpFailureMessage(RestClientResponseException ex) {
        StringBuilder message = new StringBuilder("Webhook HTTP request failed status=")
            .append(ex.getStatusCode().value());
        String retryAfter = ExternalRetryAfterHint.fromHeaders(ex.getResponseHeaders());
        if (StringUtils.hasText(retryAfter)) {
            message.append(" retryAfter=").append(retryAfter);
        }
        String responseBody = ex.getResponseBodyAsString();
        if (StringUtils.hasText(responseBody)) {
            message.append(" responseBody=").append(responseBody.replaceAll("\\s+", " ").trim());
        }
        return message.toString();
    }
}
