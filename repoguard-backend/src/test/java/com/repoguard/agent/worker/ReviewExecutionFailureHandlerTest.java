package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalCallException;
import com.repoguard.agent.review.task.ReviewFailureSummaryResolver.ReviewFailureDetails;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ReviewExecutionFailureHandlerTest {
    private final ReviewExecutionTaskTerminalWriter writer = mock(ReviewExecutionTaskTerminalWriter.class);
    private final ReviewExecutionTimelineRecorder timeline = mock(ReviewExecutionTimelineRecorder.class);
    private final ReviewExecutionMetricsRecorder metrics = mock(ReviewExecutionMetricsRecorder.class);
    private final ReviewExecutionCacheInvalidator cache = mock(ReviewExecutionCacheInvalidator.class);
    private final ReviewExecutionFailureHandler handler = new ReviewExecutionFailureHandler(
        writer, timeline, metrics, cache, new ReviewExecutionFailureClassifier(), new com.repoguard.agent.config.JacksonConfig().objectMapper());
    private final LocalDateTime now = LocalDateTime.of(2026, 10, 15, 1, 0);

    @Test
    void capturesTypedFailureBeforeConditionalTerminalWriteWithoutDiagnosticBody() {
        var task = new ReviewTask(); task.setCurrentAttemptId(42L);
        var failure = new ExternalCallException("llm", "llm_rate_limited", true, 429,
            "retryAfter=60 responseBody=private-diagnostic-body", null);
        when(writer.applyFailed(eq(task), eq(now), eq("claim"))).thenAnswer(_ -> {
            var snapshot = ReviewFailureDetails.parse(task.getFailureDetailsJson(), new com.repoguard.agent.config.JacksonConfig().objectMapper()).orElseThrow();
            assertThat(snapshot.category()).isEqualTo("llm_rate_limited");
            assertThat(snapshot.retryable()).isTrue();
            assertThat(snapshot.stage()).isEqualTo("LLM");
            assertThat(snapshot.attemptId()).isEqualTo(42L);
            assertThat(snapshot.retryAfter()).isEqualTo("60");
            assertThat(task.getFailureDetailsJson()).doesNotContain("private-diagnostic-body");
            return new ReviewExecutionTaskTerminalWriter.FailedTaskWrite(now, true);
        });
        assertThat(handler.applyFailure(task, now, "claim", failure)).isTrue();
        verify(timeline).reviewFailed(task, failure, now);
    }

    @Test
    void lostClaimDoesNotPublishFailureEffectsOrKeepUnpersistedSnapshot() {
        var task = new ReviewTask(); task.setCurrentAttemptId(42L);
        task.setFailureDetailsJson("previous");
        when(writer.applyFailed(any(), any(), any())).thenReturn(new ReviewExecutionTaskTerminalWriter.FailedTaskWrite(now, false));
        assertThat(handler.applyFailure(task, now, "old-claim", new IllegalStateException("failure"))).isFalse();
        assertThat(task.getFailureDetailsJson()).isEqualTo("previous");
        verifyNoInteractions(timeline, metrics, cache);
    }

    @Test
    void diagnosticTimelineRedactsSecretsBeforeTruncation() {
        String label = new ReviewExecutionTimelineLabelFormatter().reviewFailed(
            new IllegalStateException("api_key=test-secret-value " + "x".repeat(200)));
        assertThat(label).startsWith("Review failed:").doesNotContain("test-secret-value");
        assertThat(label.length()).isLessThanOrEqualTo(120);
    }
}
