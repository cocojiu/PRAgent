package com.repoguard.agent.notification.delivery;

import org.springframework.stereotype.Component;

@Component
public class NotificationDeliveryLogContextFormatter {

    public String safePart(String value) {
        return value == null || value.isBlank() ? "<unknown>" : value.trim();
    }
}
