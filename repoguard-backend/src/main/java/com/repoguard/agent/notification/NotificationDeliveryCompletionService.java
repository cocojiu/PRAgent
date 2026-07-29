package com.repoguard.agent.notification;

import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.notification.delivery.NotificationDeliveryCompletionDecision;
import com.repoguard.agent.notification.delivery.NotificationDeliveryResultSummary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class NotificationDeliveryCompletionService {

    private final NotificationDeliveryCompletionDecider completionDecider;
    private final NotificationDeliveryEventStateUpdater eventStateUpdater;

    @Autowired
    NotificationDeliveryCompletionService(
        NotificationDeliveryFailurePolicy deliveryFailurePolicy,
        NotificationDeliveryEventStateUpdater eventStateUpdater
    ) {
        this(new NotificationDeliveryCompletionDecider(deliveryFailurePolicy), eventStateUpdater);
    }

    NotificationDeliveryCompletionService(
        NotificationDeliveryCompletionDecider completionDecider,
        NotificationDeliveryEventStateUpdater eventStateUpdater
    ) {
        this.completionDecider = completionDecider;
        this.eventStateUpdater = eventStateUpdater;
    }

    void complete(NotificationEvent event, NotificationDeliveryResultSummary resultSummary) {
        NotificationDeliveryCompletionDecision decision = completionDecider.decide(event, resultSummary);
        if (decision.delivered()) {
            eventStateUpdater.markDelivered(event);
            return;
        }
        eventStateUpdater.markFailed(event, decision.failureDecision());
    }
}
