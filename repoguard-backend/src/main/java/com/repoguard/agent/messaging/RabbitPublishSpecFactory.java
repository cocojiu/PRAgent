package com.repoguard.agent.messaging;

import org.springframework.stereotype.Component;

@Component
public class RabbitPublishSpecFactory {

    public RabbitPublishSpec reviewTask(RabbitPublishProperties properties, Long taskId) {
        return create(properties, "review-task", taskId);
    }

    public RabbitPublishSpec notificationEvent(RabbitPublishProperties properties, Long eventId) {
        return create(properties, "notification-event", eventId);
    }

    private RabbitPublishSpec create(RabbitPublishProperties properties, String type, Long id) {
        return RabbitPublishSpec.from(properties, "%s-%d".formatted(type, id));
    }
}
