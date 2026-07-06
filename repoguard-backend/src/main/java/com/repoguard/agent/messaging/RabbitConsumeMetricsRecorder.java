package com.repoguard.agent.messaging;

import com.repoguard.agent.observability.RepoGuardMetrics;
import java.time.Duration;
import java.util.function.LongSupplier;

public class RabbitConsumeMetricsRecorder {

    private final RepoGuardMetrics metrics;
    private final LongSupplier nanoTimeSupplier;

    public RabbitConsumeMetricsRecorder(RepoGuardMetrics metrics, LongSupplier nanoTimeSupplier) {
        this.metrics = metrics;
        this.nanoTimeSupplier = nanoTimeSupplier;
    }

    public long startedAt() {
        return nanoTimeSupplier.getAsLong();
    }

    public void recordConsumed(long startedAt, String result) {
        recordConsumed(startedAt, result, "unknown");
    }

    public void recordConsumed(long startedAt, String result, String failureCategory) {
        if (metrics == null) {
            return;
        }
        metrics.rabbitMessageConsumed(Duration.ofNanos(nanoTimeSupplier.getAsLong() - startedAt), result, failureCategory);
    }

    public long elapsedMillis(long startedAt) {
        return Duration.ofNanos(nanoTimeSupplier.getAsLong() - startedAt).toMillis();
    }
}
