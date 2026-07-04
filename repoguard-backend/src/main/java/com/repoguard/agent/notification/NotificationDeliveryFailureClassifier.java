package com.repoguard.agent.notification;

import org.springframework.stereotype.Component;

@Component
class NotificationDeliveryFailureClassifier {

    String failureCategory(RuntimeException ex) {
        return ex.getClass().getSimpleName();
    }
}
