package com.repoguard.agent.notification;

import org.springframework.stereotype.Component;

@Component
class NotificationTextLimiter {

    String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
