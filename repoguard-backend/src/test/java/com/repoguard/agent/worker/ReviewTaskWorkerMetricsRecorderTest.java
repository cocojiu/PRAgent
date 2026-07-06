package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.repoguard.agent.observability.RepoGuardMetrics;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ReviewTaskWorkerMetricsRecorderTest {

    private final RepoGuardMetrics metrics = org.mockito.Mockito.mock(RepoGuardMetrics.class);
    private final TestReviewTaskWorkerClock clock = new TestReviewTaskWorkerClock();
    private final ReviewTaskWorkerMetricsRecorder recorder = new ReviewTaskWorkerMetricsRecorder(metrics, clock);

    @Test
    void recordsConsumedDurationWithResult() {
        clock.setTimes(1_000L, 6_000_000L);

        long startedAt = recorder.startedAt();
        recorder.recordConsumed(startedAt, "success");

        assertThat(startedAt).isEqualTo(1_000L);
        verify(metrics).rabbitMessageConsumed(Duration.ofNanos(5_999_000L), "success", "unknown");
    }

    @Test
    void recordsConsumedDurationWithFailureCategory() {
        clock.setTimes(1_000L, 6_000_000L);

        long startedAt = recorder.startedAt();
        recorder.recordConsumed(startedAt, "rejected", "review_execution_failed");

        assertThat(startedAt).isEqualTo(1_000L);
        verify(metrics).rabbitMessageConsumed(Duration.ofNanos(5_999_000L), "rejected", "review_execution_failed");
    }

    @Test
    void calculatesElapsedMillisFromWorkerClock() {
        clock.setTimes(2_000L, 8_002_000L);

        long startedAt = recorder.startedAt();

        assertThat(recorder.elapsedMillis(startedAt)).isEqualTo(8L);
    }

    @Test
    void noopsWhenMetricsAreUnavailable() {
        ReviewTaskWorkerMetricsRecorder disabledRecorder =
            new ReviewTaskWorkerMetricsRecorder(null, clock);
        clock.setTimes(1_000L, 2_000L);

        long startedAt = disabledRecorder.startedAt();
        disabledRecorder.recordConsumed(startedAt, "success");
    }

    private static class TestReviewTaskWorkerClock extends ReviewTaskWorkerClock {

        private long[] times = new long[0];
        private int index;

        void setTimes(long... times) {
            this.times = times;
            index = 0;
        }

        @Override
        long nanoTime() {
            return times[index++];
        }
    }
}
