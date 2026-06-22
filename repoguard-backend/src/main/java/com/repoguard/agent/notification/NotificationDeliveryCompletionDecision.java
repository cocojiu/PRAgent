package com.repoguard.agent.notification;

record NotificationDeliveryCompletionDecision(
    boolean delivered,
    NotificationDeliveryFailureDecision failureDecision
) {

    static NotificationDeliveryCompletionDecision markDelivered() {
        return new NotificationDeliveryCompletionDecision(true, null);
    }

    static NotificationDeliveryCompletionDecision failed(NotificationDeliveryFailureDecision failureDecision) {
        return new NotificationDeliveryCompletionDecision(false, failureDecision);
    }
}
