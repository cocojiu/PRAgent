package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import com.repoguard.agent.messaging.RabbitConsumeMetricsRecorderFactory;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ReviewTaskWorkerMetricsRecorderTest {

    private final RepoGuardMetrics metrics = org.mockito.Mockito.mock(RepoGuardMetrics.class);
    private final TestReviewTaskWorkerClock clock = new TestReviewTaskWorkerClock();
    private final RabbitConsumeMetricsRecorderFactory recorderFactory =
        new RabbitConsumeMetricsRecorderFactory(metrics);
    private final ReviewTaskWorkerMetricsRecorder recorder =
        new ReviewTaskWorkerMetricsRecorder(recorderFactory, clock);

    @Test
    void queueWaitUsesUtcClampsFutureValuesAndIgnoresAbsentTimestamps() {
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var observed = new ReviewTaskWorkerMetricsRecorder(recorderFactory, clock, registry);
        observed.recordQueueWait(java.time.LocalDateTime.ofInstant(clock.utcNow().minusSeconds(60), java.time.ZoneOffset.UTC));
        observed.recordQueueWait(java.time.LocalDateTime.ofInstant(clock.utcNow().plusSeconds(60), java.time.ZoneOffset.UTC));
        observed.recordQueueWait(null);
        var timer = registry.get("repoguard.review.queue.wait").timer();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(60);
    }

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
    void constructorRejectsMissingRecorderFactory() {
        assertThatThrownBy(() -> new ReviewTaskWorkerMetricsRecorder(null, clock))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("recorderFactory");
    }

    @Test
    void constructorRejectsMissingClock() {
        assertThatThrownBy(() -> new ReviewTaskWorkerMetricsRecorder(recorderFactory, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("clock");
    }

    private static class TestReviewTaskWorkerClock extends ReviewTaskWorkerClock {
        @Override
        java.time.Instant utcNow() { return java.time.Instant.parse("2026-10-14T03:00:00Z"); }

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
