package com.repoguard.agent.notification;

import org.springframework.stereotype.Component;

@Component
class NotificationCounterNormalizer {

    int safe(Integer value) {
        return value == null ? 0 : value;
    }
}
