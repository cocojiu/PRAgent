package com.repoguard.agent.notification;

record NotificationEventPayload(String eventKey, NotificationMessage message, String json) {
}
