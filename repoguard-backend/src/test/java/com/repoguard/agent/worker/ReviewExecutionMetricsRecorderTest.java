package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.review.ReviewResult;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewExecutionMetricsRecorderTest {

    private final RepoGuardMetrics metrics = org.mockito.Mockito.mock(RepoGuardMetrics.class);
    private final ReviewExecutionMetricsRecorder recorder = new ReviewExecutionMetricsRecorder(metrics);

    @Test
    void recordCompletedWritesCompletionCounterAndDuration() {
        LocalDateTime startedAt = LocalDateTime.parse("2026-07-04T20:00:00");
        LocalDateTime finishedAt = startedAt.plusSeconds(12);
        ReviewResult reviewResult = ReviewResult.completed("HIGH", List.of());

        recorder.recordCompleted(reviewResult, startedAt, finishedAt);

        verify(metrics).reviewTaskCompleted("HIGH", "COMPLETED");
        verify(metrics).reviewTaskDuration(Duration.ofSeconds(12), "completed");
    }

    @Test
    void recordFailedWritesFailureCounterAndDuration() {
        LocalDateTime startedAt = LocalDateTime.parse("2026-07-04T20:00:00");
        LocalDateTime failedAt = startedAt.plusSeconds(7);
        IllegalStateException failure = new IllegalStateException("github unavailable");

        recorder.recordFailed(failure, startedAt, failedAt);

        verify(metrics).reviewTaskFailed(failure);
        verify(metrics).reviewTaskDuration(Duration.ofSeconds(7), "failed");
    }

    @Test
    void recordGithubDiffFetchWritesDiffDurationMetric() {
        recorder.recordGithubDiffFetch(Duration.ofSeconds(5), "success");

        verify(metrics).githubDiffDuration(Duration.ofSeconds(5), "success");
    }

    @Test
    void recordStageWritesStageDurationMetric() {
        recorder.recordStage(Duration.ofMillis(250), "db_write", "success");

        verify(metrics).reviewExecutionStageDuration(Duration.ofMillis(250), "db_write", "success");
    }

    @Test
    void constructorRejectsMissingMetrics() {
        assertThatThrownBy(() -> new ReviewExecutionMetricsRecorder(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("metrics");
    }
}
