package com.repoguard.agent.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.messaging.MessagePublishException;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class NotificationEventPayloadBuilder {

    private final ObjectMapper objectMapper;
    private final NotificationEventKeyFactory eventKeyFactory;

    NotificationEventPayloadBuilder(ObjectMapper objectMapper, NotificationEventKeyFactory eventKeyFactory) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.eventKeyFactory = Objects.requireNonNull(eventKeyFactory, "eventKeyFactory");
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
        return new NotificationEventPayload(
            eventKeyFactory.create(eventType, task.getId(), batchId),
            message,
            toJson(message)
        );
    }

    private String toJson(NotificationMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException ex) {
            throw new MessagePublishException("Notification event payload serialization failed", ex);
        }
    }
}
