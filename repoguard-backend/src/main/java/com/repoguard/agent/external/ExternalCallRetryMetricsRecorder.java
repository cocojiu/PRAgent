package com.repoguard.agent.external;

import io.github.resilience4j.retry.event.RetryOnRetryEvent;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.stereotype.Component;

@Component
public class ExternalCallRetryMetricsRecorder {

    private final ExternalCallTelemetry telemetry;

    public ExternalCallRetryMetricsRecorder(ExternalCallTelemetry telemetry) {
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
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
        telemetry.recordRetry(classified, attempt);
    }
}
