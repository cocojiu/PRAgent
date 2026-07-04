package com.repoguard.agent.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.messaging.MessagePublishException;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class NotificationMessageJsonSerializer {

    private final ObjectMapper objectMapper;

    NotificationMessageJsonSerializer(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    String serialize(NotificationMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException ex) {
            throw new MessagePublishException("Notification event payload serialization failed", ex);
        }
    }
}
