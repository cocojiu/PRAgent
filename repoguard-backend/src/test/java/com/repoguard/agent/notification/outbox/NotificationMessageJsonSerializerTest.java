package com.repoguard.agent.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.messaging.MessagePublishException;
import com.repoguard.agent.notification.NotificationMessage;
import org.junit.jupiter.api.Test;

class NotificationMessageJsonSerializerTest {

    @Test
    void serializeWritesNotificationMessageJson() {
        NotificationMessageJsonSerializer serializer = new NotificationMessageJsonSerializer(new ObjectMapper());

        String json = serializer.serialize(message());

        assertThat(json).contains(
            "\"eventType\":\"REVIEW_COMPLETED\"",
            "\"taskId\":42",
            "\"detailUrl\":\"/repoguard/tasks/42\""
        );
    }

    @Test
    void serializeWrapsJsonProcessingFailure() throws JsonProcessingException {
        ObjectMapper objectMapper = org.mockito.Mockito.mock(ObjectMapper.class);
        when(objectMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {});
        NotificationMessageJsonSerializer serializer = new NotificationMessageJsonSerializer(objectMapper);

        assertThatThrownBy(() -> serializer.serialize(message()))
            .isInstanceOf(MessagePublishException.class)
            .hasMessage("Notification event payload serialization failed");
    }

    private NotificationMessage message() {
        return new NotificationMessage(
            "REVIEW_COMPLETED",
            42L,
            null,
            "octocat",
            "Hello-World",
            7,
            "Improve review flow",
            "COMPLETED",
            "HIGH",
            3,
            0,
            0,
            0,
            "/repoguard/tasks/42"
        );
    }
}
