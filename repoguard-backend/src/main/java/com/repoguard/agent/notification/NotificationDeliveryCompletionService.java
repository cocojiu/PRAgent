package com.repoguard.agent.notification;

import com.repoguard.agent.entity.NotificationEvent;
import org.springframework.stereotype.Component;

@Component
class NotificationDeliveryCompletionService {

    private final NotificationDeliveryFailurePolicy deliveryFailurePolicy;
    private final NotificationDeliveryEventStateUpdater eventStateUpdater;

    NotificationDeliveryCompletionService(
        NotificationDeliveryFailurePolicy deliveryFailurePolicy,
        NotificationDeliveryEventStateUpdater eventStateUpdater
    ) {
        this.deliveryFailurePolicy = deliveryFailurePolicy;
        this.eventStateUpdater = eventStateUpdater;
    }

    void complete(NotificationEvent event, NotificationDeliveryResultSummary resultSummary) {
        if (resultSummary.anyFailed()) {
            NotificationDeliveryFailureDecision decision = deliveryFailurePolicy.decide(event);
            eventStateUpdater.markFailed(event, decision);
            return;
        }
        eventStateUpdater.markDelivered(event);
    }
}
