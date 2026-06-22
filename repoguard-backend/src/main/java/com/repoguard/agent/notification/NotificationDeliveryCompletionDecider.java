package com.repoguard.agent.notification;

import com.repoguard.agent.entity.NotificationEvent;
import org.springframework.stereotype.Component;

@Component
class NotificationDeliveryCompletionDecider {

    private final NotificationDeliveryFailurePolicy deliveryFailurePolicy;

    NotificationDeliveryCompletionDecider(NotificationDeliveryFailurePolicy deliveryFailurePolicy) {
        this.deliveryFailurePolicy = deliveryFailurePolicy;
    }

    NotificationDeliveryCompletionDecision decide(
        NotificationEvent event,
        NotificationDeliveryResultSummary resultSummary
    ) {
        if (resultSummary.anyFailed()) {
            return NotificationDeliveryCompletionDecision.failed(deliveryFailurePolicy.decide(event));
        }
        return NotificationDeliveryCompletionDecision.markDelivered();
    }
}
