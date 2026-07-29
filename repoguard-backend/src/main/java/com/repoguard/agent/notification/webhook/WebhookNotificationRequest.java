package com.repoguard.agent.notification.webhook;

record WebhookNotificationRequest(
    String webhookUrl,
    String secret,
    String failureMessage
) {

    boolean ready() {
        return failureMessage == null;
    }
}
