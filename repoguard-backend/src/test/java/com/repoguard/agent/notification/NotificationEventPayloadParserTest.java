package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.entity.NotificationEvent;
import org.junit.jupiter.api.Test;

class NotificationEventPayloadParserTest {

    private final NotificationEventPayloadParser parser = new NotificationEventPayloadParser(new ObjectMapper());

    @Test
    void parsesNotificationMessageFromEventPayload() {
        NotificationEvent event = event("""
            {
              "eventType":"REVIEW_COMPLETED",
              "taskId":42,
              "batchId":null,
              "organization":"octocat",
              "repository":"Hello-World",
              "prNumber":7,
              "title":"Improve review flow",
              "status":"COMPLETED",
              "riskLevel":"HIGH",
              "findingCount":3,
              "commentSucceededCount":0,
              "commentFailedCount":0,
              "commentSkippedCount":0,
              "detailUrl":"/repoguard/tasks/42"
            }
            """);

        NotificationMessage message = parser.parse(event);

        assertThat(message).isEqualTo(new NotificationMessage(
            NotificationEventType.REVIEW_COMPLETED.code(),
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
        ));
    }

    @Test
    void invalidPayloadThrowsStableApplicationException() {
        NotificationEvent event = event("{invalid-json");

        assertThatThrownBy(() -> parser.parse(event))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Notification payload parse failed");
    }

    private NotificationEvent event(String payload) {
        NotificationEvent event = new NotificationEvent();
        event.setPayload(payload);
        return event;
    }
}
