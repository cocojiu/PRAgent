package com.repoguard.agent.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.notification.NotificationEventType;
import com.repoguard.agent.notification.NotificationMessage;
import com.repoguard.agent.review.quality.LlmModelReleaseMetricSnapshot;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationEventPayloadBuilderTest {

    private final NotificationEventPayloadBuilder builder =
        new NotificationEventPayloadBuilder(
            new NotificationEventKeyFactory(),
            new NotificationMessageJsonSerializer(new com.fasterxml.jackson.databind.ObjectMapper())
        );

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

    @Test
    void buildsReleaseAlertPayloadWithoutTaskIdentity() {
        LlmModelReleaseMetricSnapshot snapshot = new LlmModelReleaseMetricSnapshot(
            8L, 7L, "release-next", "openai", "gpt-next",
            LocalDateTime.of(2026, 9, 3, 1, 0), LocalDateTime.of(2026, 9, 3, 2, 0),
            12L, 1200L, new BigDecimal("0.1200"), 20_000L, 1L, 3L, 0L,
            "ALERT", List.of("P95_LATENCY_ABOVE_RUNTIME_THRESHOLD"), "NOTIFY",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            LocalDateTime.of(2026, 9, 3, 2, 0), LocalDateTime.of(2026, 9, 3, 2, 0)
        );

        NotificationEventPayload payload = builder.buildReleaseAlert(snapshot);

        assertThat(payload.eventKey()).startsWith("MODEL_RELEASE_ALERT:release-next:2026-09-03T01:00:");
        assertThat(payload.message()).isEqualTo(new NotificationMessage(
            "MODEL_RELEASE_ALERT", null, null, "*", "*", null, "LLM 模型发布运行告警",
            "ALERT", "HIGH", 12, 0, 0, 0,
            "/repoguard/config/review-calibration/release-center",
            "版本 release-next（openai/gpt-next）触发 P95_LATENCY_ABOVE_RUNTIME_THRESHOLD；阈值动作：NOTIFY；样本 12；窗口 2026-09-03T01:00 ~ 2026-09-03T02:00"
        ));
        assertThat(payload.json()).contains("MODEL_RELEASE_ALERT", "alertSummary");
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
