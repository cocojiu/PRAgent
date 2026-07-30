package com.repoguard.agent.notification.delivery;

public record NotificationSendResult(
    boolean success,
    String requestId,
    String message
) {
    public static NotificationSendResult success(String requestId, String message) {
        return new NotificationSendResult(true, requestId, message);
    }

    public static NotificationSendResult failed(String requestId, String message) {
        return new NotificationSendResult(false, requestId, message);
    }
}
