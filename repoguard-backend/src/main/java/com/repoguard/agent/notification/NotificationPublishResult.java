package com.repoguard.agent.notification;

record NotificationPublishResult(boolean attempted, boolean success, String failureReason) {

    private static final String NONE = "none";

    static NotificationPublishResult published() {
        return new NotificationPublishResult(true, true, NONE);
    }

    static NotificationPublishResult skipped() {
        return new NotificationPublishResult(false, false, "claim_lost");
    }

    static NotificationPublishResult failed(String failureReason) {
        return new NotificationPublishResult(true, false, failureReason);
    }
}
