package com.repoguard.agent.notification;

record NotificationPublishResult(boolean success, String failureReason) {

    private static final String NONE = "none";

    static NotificationPublishResult published() {
        return new NotificationPublishResult(true, NONE);
    }

    static NotificationPublishResult failed(String failureReason) {
        return new NotificationPublishResult(false, failureReason);
    }
}
