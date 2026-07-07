package com.repoguard.agent.external;

import com.repoguard.agent.observability.RepoGuardMetrics;
import io.github.resilience4j.retry.event.RetryOnRetryEvent;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.stereotype.Component;

@Component
public class ExternalCallRetryMetricsRecorder {

    private final RepoGuardMetrics metrics;

    public ExternalCallRetryMetricsRecorder(RepoGuardMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public void record(
        Function<RuntimeException, ExternalCallException> classifier,
        RetryOnRetryEvent event
    ) {
        Objects.requireNonNull(classifier, "classifier");
        if (event == null || !(event.getLastThrowable() instanceof RuntimeException runtimeException)) {
            return;
        }
        metrics.externalCallRetried(classifier.apply(runtimeException), event.getNumberOfRetryAttempts());
    }
}
