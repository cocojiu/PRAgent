package com.repoguard.agent.notification.delivery;

import com.repoguard.agent.entity.NotificationEvent;
import org.springframework.stereotype.Component;

@Component
public class NotificationDeliveryCompletionDecider {

    private final NotificationDeliveryFailurePolicy deliveryFailurePolicy;

    public NotificationDeliveryCompletionDecider(NotificationDeliveryFailurePolicy deliveryFailurePolicy) {
        this.deliveryFailurePolicy = deliveryFailurePolicy;
    }

    public NotificationDeliveryCompletionDecision decide(
        NotificationEvent event,
        NotificationDeliveryResultSummary resultSummary
    ) {
        if (resultSummary.anyFailed()) {
            return NotificationDeliveryCompletionDecision.failed(deliveryFailurePolicy.decide(event));
        }
        return NotificationDeliveryCompletionDecision.markDelivered();
    }
}
