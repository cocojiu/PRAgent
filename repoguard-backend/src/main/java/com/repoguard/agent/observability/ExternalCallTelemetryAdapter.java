package com.repoguard.agent.observability;

import com.repoguard.agent.external.ExternalCallException;
import com.repoguard.agent.external.ExternalCallTelemetry;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class ExternalCallTelemetryAdapter implements ExternalCallTelemetry {

    private final RepoGuardMetrics metrics;
    private final ObservabilityThresholdMonitor thresholdMonitor;

    public ExternalCallTelemetryAdapter(
        RepoGuardMetrics metrics,
        ObservabilityThresholdMonitor thresholdMonitor
    ) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.thresholdMonitor = Objects.requireNonNull(thresholdMonitor, "thresholdMonitor");
    }

    @Override
    public void recordRetry(ExternalCallException failure, int attempt) {
        metrics.externalCallRetried(failure, attempt);
        thresholdMonitor.externalCallRetry(failure, attempt);
    }
}
