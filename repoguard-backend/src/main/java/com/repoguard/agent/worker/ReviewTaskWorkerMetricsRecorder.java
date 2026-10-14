package com.repoguard.agent.worker;

import com.repoguard.agent.messaging.RabbitConsumeMetricsRecorder;
import com.repoguard.agent.messaging.RabbitConsumeMetricsRecorderFactory;
import com.repoguard.agent.observability.LlmUsageMetricsRecorder;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class ReviewTaskWorkerMetricsRecorder {

    private final RabbitConsumeMetricsRecorder recorder;
    private final LlmUsageMetricsRecorder usageMetrics;

    ReviewTaskWorkerMetricsRecorder(
        RabbitConsumeMetricsRecorderFactory recorderFactory,
        ReviewTaskWorkerClock clock
    ) {
        this(recorderFactory, clock, null);
    }

    @Autowired
    ReviewTaskWorkerMetricsRecorder(RabbitConsumeMetricsRecorderFactory recorderFactory,
        ReviewTaskWorkerClock clock, LlmUsageMetricsRecorder usageMetrics) {
        this.recorder = Objects.requireNonNull(recorderFactory, "recorderFactory")
            .create(Objects.requireNonNull(clock, "clock")::nanoTime);
        this.usageMetrics = usageMetrics;
    }

    void recordQueueWait(LocalDateTime queuedAt) {
        if (usageMetrics != null) usageMetrics.queueWait(queuedAt);
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
