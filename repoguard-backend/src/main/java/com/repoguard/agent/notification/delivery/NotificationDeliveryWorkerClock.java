package com.repoguard.agent.notification.delivery;

import org.springframework.stereotype.Component;

@Component
public class NotificationDeliveryWorkerClock {

    public long nanoTime() {
        return System.nanoTime();
    }
}
