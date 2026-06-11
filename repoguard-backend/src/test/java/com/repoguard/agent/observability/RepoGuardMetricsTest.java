package com.repoguard.agent.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.external.ExternalCallException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class RepoGuardMetricsTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final RepoGuardMetrics metrics = new RepoGuardMetrics(meterRegistry);

    @Test
    void recordsReviewTaskCountersWithStableTags() {
        metrics.reviewTaskCreated("MANUAL_INPUT");
        metrics.reviewTaskCompleted("HIGH", "FALLBACK");
        metrics.reviewTaskFailed(new IllegalStateException("failed"));

        assertThat(counter("repoguard.review.task.created", "source", "manual_input")).isEqualTo(1.0);
        assertThat(counter(
            "repoguard.review.task.completed",
            "risk_level", "high",
            "llm_status", "fallback"
        )).isEqualTo(1.0);
        assertThat(counter(
            "repoguard.review.task.failed",
            "category", "illegalstateexception",
            "retryable", "unknown"
        )).isEqualTo(1.0);
    }

    @Test
    void recordsExternalCallFailureWithClassificationTags() {
        metrics.externalCallFailed(new ExternalCallException(
            "GitHub",
            "github_rate_limited",
            true,
            429,
            "too many requests",
            new RuntimeException("429")
        ));

        assertThat(counter(
            "repoguard.external.call.failed",
            "system", "github",
            "category", "github_rate_limited",
            "retryable", "true",
            "status", "429"
        )).isEqualTo(1.0);
    }

    @Test
    void recordsRabbitPublishAndCompensationCounters() {
        metrics.rabbitPublishFailed("Confirm timed out");
        metrics.rabbitPublishCompensationSucceeded();
        metrics.rabbitPublishCompensationFailed("Publisher confirm nacked");

        assertThat(counter(
            "repoguard.rabbit.publish.failed",
            "reason", "confirm_timed_out"
        )).isEqualTo(1.0);
        assertThat(counter(
            "repoguard.rabbit.publish.compensation",
            "result", "success"
        )).isEqualTo(1.0);
        assertThat(counter(
            "repoguard.rabbit.publish.compensation",
            "result", "failed",
            "reason", "publisher_confirm_nacked"
        )).isEqualTo(1.0);
    }

    private double counter(String name, String... tags) {
        return meterRegistry.find(name).tags(tags).counter().count();
    }
}
