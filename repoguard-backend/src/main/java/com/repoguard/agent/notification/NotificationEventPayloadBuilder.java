package com.repoguard.agent.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.messaging.MessagePublishException;
import org.springframework.stereotype.Component;

@Component
class NotificationEventPayloadBuilder {

    private final ObjectMapper objectMapper;

    NotificationEventPayloadBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    NotificationEventPayload build(
        String eventType,
        ReviewTask task,
        Long batchId,
        int findingCount,
        int commentSucceededCount,
        int commentFailedCount,
        int commentSkippedCount
    ) {
        NotificationMessage message = new NotificationMessage(
            eventType,
            task.getId(),
            batchId,
            task.getOrganization(),
            task.getRepository(),
            task.getPrNumber(),
            task.getTitle(),
            task.getStatus(),
            task.getRiskLevel(),
            findingCount,
            commentSucceededCount,
            commentFailedCount,
            commentSkippedCount,
            "/repoguard/tasks/" + task.getId()
        );
        return new NotificationEventPayload(eventKey(eventType, task.getId(), batchId), message, toJson(message));
    }

    private String eventKey(String eventType, Long taskId, Long batchId) {
        return batchId == null ? eventType + ":" + taskId : eventType + ":" + taskId + ":" + batchId;
    }

    private String toJson(NotificationMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException ex) {
            throw new MessagePublishException("Notification event payload serialization failed", ex);
        }
    }
}
