package com.repoguard.agent.messaging;

import com.repoguard.agent.observability.RepoGuardMetrics;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.springframework.stereotype.Component;

@Component
public class RabbitConsumeMetricsRecorderFactory {

    private final RepoGuardMetrics metrics;

    public RabbitConsumeMetricsRecorderFactory(RepoGuardMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public RabbitConsumeMetricsRecorder create(LongSupplier nanoTimeSupplier) {
        return new RabbitConsumeMetricsRecorder(metrics, nanoTimeSupplier);
    }
}
