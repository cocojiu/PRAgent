package com.repoguard.agent.notification;

import com.repoguard.agent.common.SensitiveTextSanitizer;
import org.springframework.stereotype.Component;

@Component
class WebhookNotificationResponseEvaluator {

    private static final int MAX_RESPONSE_LENGTH = 512;

    NotificationSendResult evaluate(Object response) {
        String responseText = response == null ? "" : safeMessage(response.toString());
        if (isSuccessResponse(responseText)) {
            return NotificationSendResult.success(null, responseText);
        }
        return NotificationSendResult.failed(null, responseText);
    }

    NotificationSendResult failure(RuntimeException ex) {
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
        return truncate(SensitiveTextSanitizer.sanitize(value), MAX_RESPONSE_LENGTH);
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
