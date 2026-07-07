package com.repoguard.agent.messaging;

import com.repoguard.agent.observability.RepoGuardMetrics;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class RabbitPublishFailureMetricsRecorder {

    private final RepoGuardMetrics metrics;

    public RabbitPublishFailureMetricsRecorder(RepoGuardMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public void recordFailed(RabbitPublishFailurePhase failurePhase, String reason) {
        Objects.requireNonNull(failurePhase, "failurePhase");
        recordFailed(failurePhase.code(), reason);
    }

    private void recordFailed(String failurePhase, String reason) {
        metrics.rabbitPublishFailed(failurePhase, reason);
    }
}
