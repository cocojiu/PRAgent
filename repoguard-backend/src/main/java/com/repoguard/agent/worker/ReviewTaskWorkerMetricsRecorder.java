package com.repoguard.agent.worker;

import com.repoguard.agent.messaging.RabbitConsumeMetricsRecorder;
import com.repoguard.agent.observability.RepoGuardMetrics;
import org.springframework.stereotype.Component;

@Component
class ReviewTaskWorkerMetricsRecorder {

    private final RabbitConsumeMetricsRecorder recorder;

    ReviewTaskWorkerMetricsRecorder(RepoGuardMetrics metrics, ReviewTaskWorkerClock clock) {
        this.recorder = new RabbitConsumeMetricsRecorder(metrics, clock::nanoTime);
    }

    long startedAt() {
        return recorder.startedAt();
    }

    void recordConsumed(long startedAt, String result) {
        recorder.recordConsumed(startedAt, result);
    }

    void recordConsumed(long startedAt, String result, String failureCategory) {
        recorder.recordConsumed(startedAt, result, failureCategory);
    }

    long elapsedMillis(long startedAt) {
        return recorder.elapsedMillis(startedAt);
    }
}
