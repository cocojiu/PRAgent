package com.repoguard.agent.notification.dispatch;

import org.springframework.stereotype.Component;

@Component
public class NotificationCounterNormalizer {

    int safe(Integer value) {
        return value == null ? 0 : value;
    }
}
