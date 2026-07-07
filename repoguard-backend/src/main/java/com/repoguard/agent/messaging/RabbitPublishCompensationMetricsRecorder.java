package com.repoguard.agent.messaging;

import com.repoguard.agent.observability.RepoGuardMetrics;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class RabbitPublishCompensationMetricsRecorder {

    private final RepoGuardMetrics metrics;

    public RabbitPublishCompensationMetricsRecorder(RepoGuardMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public void recordSucceeded(String failurePhase) {
        metrics.rabbitPublishCompensationSucceeded(failurePhase);
    }

    public void recordFailed(String failurePhase, String reason) {
        metrics.rabbitPublishCompensationFailed(failurePhase, reason);
    }

    public void record(RabbitPublishCompensationOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome.success()) {
            recordSucceeded(outcome.failurePhase().code());
            return;
        }
        recordFailed(outcome.failurePhase().code(), outcome.reason());
    }
}
