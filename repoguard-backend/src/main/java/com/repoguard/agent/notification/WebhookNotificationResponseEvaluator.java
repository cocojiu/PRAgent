package com.repoguard.agent.notification;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
class WebhookNotificationResponseEvaluator {

    private static final int MAX_RESPONSE_LENGTH = 512;
    private static final Pattern SENSITIVE_QUERY_PARAM_PATTERN = Pattern.compile(
        "(?i)([?&](?:access_token|token|secret|key|sign)=)[^\\s&\"']+"
    );
    private static final Pattern SENSITIVE_ASSIGNMENT_PATTERN = Pattern.compile(
        "(?i)(\\b(?:access[_-]?token|token|secret|api[-_ ]?key|sign)\\b\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;}&]+)"
    );

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
        return truncate(maskSensitiveValues(value), MAX_RESPONSE_LENGTH);
    }

    private String maskSensitiveValues(String value) {
        if (value == null) {
            return null;
        }
        String masked = SENSITIVE_QUERY_PARAM_PATTERN.matcher(value).replaceAll("$1****");
        return SENSITIVE_ASSIGNMENT_PATTERN.matcher(masked).replaceAll("$1****");
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
