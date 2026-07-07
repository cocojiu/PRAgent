package com.repoguard.agent.external;

import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.observability.ObservabilityThresholdMonitor;
import io.github.resilience4j.retry.event.RetryOnRetryEvent;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.stereotype.Component;

@Component
public class ExternalCallRetryMetricsRecorder {

    private final RepoGuardMetrics metrics;
    private final ObservabilityThresholdMonitor thresholdMonitor;

    public ExternalCallRetryMetricsRecorder(
        RepoGuardMetrics metrics,
        ObservabilityThresholdMonitor thresholdMonitor
    ) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.thresholdMonitor = Objects.requireNonNull(thresholdMonitor, "thresholdMonitor");
    }

    public void record(
        Function<RuntimeException, ExternalCallException> classifier,
        RetryOnRetryEvent event
    ) {
        Objects.requireNonNull(classifier, "classifier");
        if (event == null || !(event.getLastThrowable() instanceof RuntimeException runtimeException)) {
            return;
        }
        ExternalCallException classified = classifier.apply(runtimeException);
        int attempt = event.getNumberOfRetryAttempts();
        metrics.externalCallRetried(classified, attempt);
        thresholdMonitor.externalCallRetry(classified, attempt);
    }
}
