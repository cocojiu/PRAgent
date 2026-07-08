package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ReviewExecutionStageTimerTest {

    private final TestReviewExecutionClock clock = new TestReviewExecutionClock();
    private final ReviewExecutionMetricsRecorder metricsRecorder =
        org.mockito.Mockito.mock(ReviewExecutionMetricsRecorder.class);
    private final ReviewExecutionStageTimer timer = new ReviewExecutionStageTimer(clock, metricsRecorder);

    @Test
    void recordsSuccessfulStageDuration() {
        clock.setTimes("2026-07-09T10:15:00", "2026-07-09T10:15:02");

        String result = timer.record("review", () -> "ok");

        assertThat(result).isEqualTo("ok");
        verify(metricsRecorder).recordStage(Duration.ofSeconds(2), "review", "success");
    }

    @Test
    void recordsFailedStageDurationAndRethrows() {
        IllegalStateException failure = new IllegalStateException("llm unavailable");
        clock.setTimes("2026-07-09T10:20:00", "2026-07-09T10:20:03");

        assertThatThrownBy(() -> timer.record("review", () -> {
            throw failure;
        })).isSameAs(failure);

        verify(metricsRecorder).recordStage(Duration.ofSeconds(3), "review", "failed");
    }

    private static class TestReviewExecutionClock extends ReviewExecutionClock {

        private LocalDateTime[] times = new LocalDateTime[0];
        private int index;

        void setTimes(String... isoDateTimes) {
            times = java.util.Arrays.stream(isoDateTimes)
                .map(LocalDateTime::parse)
                .toArray(LocalDateTime[]::new);
            index = 0;
        }

        @Override
        LocalDateTime now() {
            return times[index++];
        }
    }
}
