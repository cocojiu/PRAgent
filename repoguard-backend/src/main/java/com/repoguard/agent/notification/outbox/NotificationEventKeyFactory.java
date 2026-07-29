package com.repoguard.agent.notification.outbox;

import org.springframework.stereotype.Component;

@Component
public class NotificationEventKeyFactory {

    public String create(String eventType, Long taskId, Long batchId) {
        return batchId == null ? eventType + ":" + taskId : eventType + ":" + taskId + ":" + batchId;
    }
}
