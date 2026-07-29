package com.repoguard.agent.notification.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.notification.NotificationMessage;
import org.springframework.stereotype.Component;

@Component
class NotificationEventPayloadParser {

    private final ObjectMapper objectMapper;

    NotificationEventPayloadParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    NotificationMessage parse(NotificationEvent event) {
        try {
            return objectMapper.readValue(event.getPayload(), NotificationMessage.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Notification payload parse failed", ex);
        }
    }
}
