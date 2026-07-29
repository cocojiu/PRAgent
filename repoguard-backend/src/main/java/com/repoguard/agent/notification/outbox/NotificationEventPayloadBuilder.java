package com.repoguard.agent.notification.outbox;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.notification.NotificationMessage;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class NotificationEventPayloadBuilder {

    private final NotificationEventKeyFactory eventKeyFactory;
    private final NotificationMessageJsonSerializer messageJsonSerializer;

    public NotificationEventPayloadBuilder(
        NotificationEventKeyFactory eventKeyFactory,
        NotificationMessageJsonSerializer messageJsonSerializer
    ) {
        this.eventKeyFactory = Objects.requireNonNull(eventKeyFactory, "eventKeyFactory");
        this.messageJsonSerializer = Objects.requireNonNull(messageJsonSerializer, "messageJsonSerializer");
    }

    public NotificationEventPayload build(
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
