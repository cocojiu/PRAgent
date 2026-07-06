package com.repoguard.agent.notification;

import com.repoguard.agent.observability.RepoGuardMetrics;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
class NotificationDeliveryWorkerMetricsRecorder {

    private final RepoGuardMetrics metrics;
    private final NotificationDeliveryWorkerClock clock;

    NotificationDeliveryWorkerMetricsRecorder(RepoGuardMetrics metrics, NotificationDeliveryWorkerClock clock) {
        this.metrics = metrics;
        this.clock = clock;
    }

    long startedAt() {
        return clock.nanoTime();
    }

    void recordConsumed(long startedAt, String result) {
        recordConsumed(startedAt, result, "unknown");
    }

    void recordConsumed(long startedAt, String result, String failureCategory) {
        if (metrics == null) {
            return;
        }
        metrics.rabbitMessageConsumed(Duration.ofNanos(clock.nanoTime() - startedAt), result, failureCategory);
    }

    long elapsedMillis(long startedAt) {
        return Duration.ofNanos(clock.nanoTime() - startedAt).toMillis();
    }
}
