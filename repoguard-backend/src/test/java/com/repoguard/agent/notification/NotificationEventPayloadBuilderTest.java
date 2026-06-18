package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.entity.ReviewTask;
import org.junit.jupiter.api.Test;

class NotificationEventPayloadBuilderTest {

    private final NotificationEventPayloadBuilder builder = new NotificationEventPayloadBuilder(new ObjectMapper());

    @Test
    void buildsPayloadForReviewTaskEvent() {
        NotificationEventPayload payload = builder.build(
            NotificationEventType.REVIEW_COMPLETED.code(),
            task(),
            null,
            3,
            0,
            0,
            0
        );

        assertThat(payload.eventKey()).isEqualTo("REVIEW_COMPLETED:42");
        assertThat(payload.message()).isEqualTo(new NotificationMessage(
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
        ));
        assertThat(payload.json()).contains(
            "\"eventType\":\"REVIEW_COMPLETED\"",
            "\"taskId\":42",
            "\"detailUrl\":\"/repoguard/tasks/42\"",
            "\"findingCount\":3"
        );
    }

    @Test
    void includesBatchIdInEventKeyAndMessage() {
        NotificationEventPayload payload = builder.build(
            NotificationEventType.GITHUB_COMMENT_PUBLISHED.code(),
            task(),
            99L,
            5,
            2,
            1,
            2
        );

        assertThat(payload.eventKey()).isEqualTo("GITHUB_COMMENT_PUBLISHED:42:99");
        assertThat(payload.message().batchId()).isEqualTo(99L);
        assertThat(payload.json()).contains(
            "\"batchId\":99",
            "\"commentSucceededCount\":2",
            "\"commentFailedCount\":1",
            "\"commentSkippedCount\":2"
        );
    }

    private ReviewTask task() {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        task.setOrganization("octocat");
        task.setRepository("Hello-World");
        task.setPrNumber(7);
        task.setTitle("Improve review flow");
        task.setStatus("COMPLETED");
        task.setRiskLevel("HIGH");
        return task;
    }
}
