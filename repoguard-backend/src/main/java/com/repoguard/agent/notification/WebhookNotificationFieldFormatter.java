package com.repoguard.agent.notification;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class WebhookNotificationFieldFormatter {

    String text(String value) {
        if (!StringUtils.hasText(value)) {
            return "-";
        }
        return value.replace("\r", " ").replace("\n", " ").trim();
    }

    int count(Integer value) {
        return value == null ? 0 : value;
    }
}
