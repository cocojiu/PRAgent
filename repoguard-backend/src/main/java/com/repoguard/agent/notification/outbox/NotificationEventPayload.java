package com.repoguard.agent.notification.outbox;

import com.repoguard.agent.notification.NotificationMessage;

public record NotificationEventPayload(String eventKey, NotificationMessage message, String json) {
}
