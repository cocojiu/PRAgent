package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.repoguard.agent.observability.RepoGuardMetrics;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class NotificationDeliveryWorkerMetricsRecorderTest {

    private final RepoGuardMetrics metrics = org.mockito.Mockito.mock(RepoGuardMetrics.class);
    private final TestNotificationDeliveryWorkerClock clock = new TestNotificationDeliveryWorkerClock();
    private final NotificationDeliveryWorkerMetricsRecorder recorder =
        new NotificationDeliveryWorkerMetricsRecorder(metrics, clock);

    @Test
    void recordsConsumedDurationWithResult() {
        clock.setTimes(1_000L, 6_000_000L);

        long startedAt = recorder.startedAt();
        recorder.recordConsumed(startedAt, "success");

        assertThat(startedAt).isEqualTo(1_000L);
        verify(metrics).rabbitMessageConsumed(Duration.ofNanos(5_999_000L), "success");
    }

    @Test
    void calculatesElapsedMillisFromWorkerClock() {
        clock.setTimes(2_000L, 8_002_000L);

        long startedAt = recorder.startedAt();

        assertThat(recorder.elapsedMillis(startedAt)).isEqualTo(8L);
    }

    @Test
    void noopsWhenMetricsAreUnavailable() {
        NotificationDeliveryWorkerMetricsRecorder disabledRecorder =
            new NotificationDeliveryWorkerMetricsRecorder(null, clock);
        clock.setTimes(1_000L, 2_000L);

        long startedAt = disabledRecorder.startedAt();
        disabledRecorder.recordConsumed(startedAt, "success");
    }

    private static class TestNotificationDeliveryWorkerClock extends NotificationDeliveryWorkerClock {

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
