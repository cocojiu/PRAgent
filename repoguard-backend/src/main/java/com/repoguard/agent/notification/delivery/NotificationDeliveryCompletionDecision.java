package com.repoguard.agent.notification.delivery;

public record NotificationDeliveryCompletionDecision(
    boolean delivered,
    NotificationDeliveryFailureDecision failureDecision
) {

    public static NotificationDeliveryCompletionDecision markDelivered() {
        return new NotificationDeliveryCompletionDecision(true, null);
    }

    public static NotificationDeliveryCompletionDecision failed(NotificationDeliveryFailureDecision failureDecision) {
        return new NotificationDeliveryCompletionDecision(false, failureDecision);
    }
}
