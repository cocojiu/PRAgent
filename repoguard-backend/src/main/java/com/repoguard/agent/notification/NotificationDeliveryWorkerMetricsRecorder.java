package com.repoguard.agent.notification;

import com.repoguard.agent.messaging.RabbitConsumeMetricsRecorder;
import com.repoguard.agent.messaging.RabbitConsumeMetricsRecorderFactory;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class NotificationDeliveryWorkerMetricsRecorder {

    private final RabbitConsumeMetricsRecorder recorder;

    NotificationDeliveryWorkerMetricsRecorder(
        RabbitConsumeMetricsRecorderFactory recorderFactory,
        NotificationDeliveryWorkerClock clock
    ) {
        this.recorder = Objects.requireNonNull(recorderFactory, "recorderFactory")
            .create(Objects.requireNonNull(clock, "clock")::nanoTime);
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
