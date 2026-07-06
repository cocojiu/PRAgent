package com.repoguard.agent.worker;

import com.repoguard.agent.observability.RepoGuardMetrics;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
class ReviewTaskWorkerMetricsRecorder {

    private final RepoGuardMetrics metrics;
    private final ReviewTaskWorkerClock clock;

    ReviewTaskWorkerMetricsRecorder(RepoGuardMetrics metrics, ReviewTaskWorkerClock clock) {
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
