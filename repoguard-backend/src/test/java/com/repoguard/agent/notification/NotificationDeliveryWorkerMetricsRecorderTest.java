package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        verify(metrics).rabbitMessageConsumed(Duration.ofNanos(5_999_000L), "success", "unknown");
    }

    @Test
    void recordsConsumedDurationWithFailureCategory() {
        clock.setTimes(1_000L, 6_000_000L);

        long startedAt = recorder.startedAt();
        recorder.recordConsumed(startedAt, "rejected", "notification_http_rate_limited");

        assertThat(startedAt).isEqualTo(1_000L);
        verify(metrics).rabbitMessageConsumed(
            Duration.ofNanos(5_999_000L),
            "rejected",
            "notification_http_rate_limited"
        );
    }

    @Test
    void calculatesElapsedMillisFromWorkerClock() {
        clock.setTimes(2_000L, 8_002_000L);

        long startedAt = recorder.startedAt();

        assertThat(recorder.elapsedMillis(startedAt)).isEqualTo(8L);
    }

    @Test
    void constructorRejectsMissingMetrics() {
        assertThatThrownBy(() -> new NotificationDeliveryWorkerMetricsRecorder(null, clock))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("metrics");
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
