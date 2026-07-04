package com.repoguard.agent.notification;

import org.springframework.stereotype.Component;

@Component
class NotificationDeliveryWorkerClock {

    long nanoTime() {
        return System.nanoTime();
    }
}
