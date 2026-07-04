package com.repoguard.agent.notification;

import org.springframework.stereotype.Component;

@Component
class NotificationDeliveryLogContextFormatter {

    String safePart(String value) {
        return value == null || value.isBlank() ? "<unknown>" : value.trim();
    }
}
