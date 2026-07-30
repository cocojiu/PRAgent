package com.repoguard.agent.notification.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.messaging.MessagePublishException;
import com.repoguard.agent.notification.NotificationMessage;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class NotificationMessageJsonSerializer {

    private final ObjectMapper objectMapper;

    public NotificationMessageJsonSerializer(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public String serialize(NotificationMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException ex) {
            throw new MessagePublishException("Notification event payload serialization failed", ex);
        }
    }
}
