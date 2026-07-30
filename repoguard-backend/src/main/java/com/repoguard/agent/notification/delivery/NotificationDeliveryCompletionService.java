package com.repoguard.agent.notification.delivery;

import com.repoguard.agent.entity.NotificationEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class NotificationDeliveryCompletionService {

    private final NotificationDeliveryCompletionDecider completionDecider;
    private final NotificationDeliveryEventStateUpdater eventStateUpdater;

    @Autowired
    public NotificationDeliveryCompletionService(
        NotificationDeliveryFailurePolicy deliveryFailurePolicy,
        NotificationDeliveryEventStateUpdater eventStateUpdater
    ) {
        this(new NotificationDeliveryCompletionDecider(deliveryFailurePolicy), eventStateUpdater);
    }

    public NotificationDeliveryCompletionService(
        NotificationDeliveryCompletionDecider completionDecider,
        NotificationDeliveryEventStateUpdater eventStateUpdater
    ) {
        this.completionDecider = completionDecider;
        this.eventStateUpdater = eventStateUpdater;
    }

    public void complete(NotificationEvent event, NotificationDeliveryResultSummary resultSummary) {
        NotificationDeliveryCompletionDecision decision = completionDecider.decide(event, resultSummary);
        if (decision.delivered()) {
            eventStateUpdater.markDelivered(event);
            return;
        }
        eventStateUpdater.markFailed(event, decision.failureDecision());
    }
}
