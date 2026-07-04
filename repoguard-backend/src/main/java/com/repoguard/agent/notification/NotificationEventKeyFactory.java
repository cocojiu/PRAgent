package com.repoguard.agent.notification;

import org.springframework.stereotype.Component;

@Component
class NotificationEventKeyFactory {

    String create(String eventType, Long taskId, Long batchId) {
        return batchId == null ? eventType + ":" + taskId : eventType + ":" + taskId + ":" + batchId;
    }
}
