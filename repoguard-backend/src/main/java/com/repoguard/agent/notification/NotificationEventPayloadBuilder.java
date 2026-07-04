package com.repoguard.agent.notification;

import com.repoguard.agent.entity.ReviewTask;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class NotificationEventPayloadBuilder {

    private final NotificationEventKeyFactory eventKeyFactory;
    private final NotificationMessageJsonSerializer messageJsonSerializer;

    NotificationEventPayloadBuilder(
        NotificationEventKeyFactory eventKeyFactory,
        NotificationMessageJsonSerializer messageJsonSerializer
    ) {
        this.eventKeyFactory = Objects.requireNonNull(eventKeyFactory, "eventKeyFactory");
        this.messageJsonSerializer = Objects.requireNonNull(messageJsonSerializer, "messageJsonSerializer");
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
            messageJsonSerializer.serialize(message)
        );
    }
}
