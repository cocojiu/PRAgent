package com.repoguard.agent.notification.delivery;

import com.repoguard.agent.messaging.RabbitConsumeMetricsRecorder;
import com.repoguard.agent.messaging.RabbitConsumeMetricsRecorderFactory;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class NotificationDeliveryWorkerMetricsRecorder {

    private final RabbitConsumeMetricsRecorder recorder;

    public NotificationDeliveryWorkerMetricsRecorder(
        RabbitConsumeMetricsRecorderFactory recorderFactory,
        NotificationDeliveryWorkerClock clock
    ) {
        this.recorder = Objects.requireNonNull(recorderFactory, "recorderFactory")
            .create(Objects.requireNonNull(clock, "clock")::nanoTime);
    }

    public long startedAt() {
        return recorder.startedAt();
    }

    public void recordConsumed(long startedAt, String result) {
        recorder.recordConsumed(startedAt, result);
    }

    public void recordConsumed(long startedAt, String result, String failureCategory) {
        recorder.recordConsumed(startedAt, result, failureCategory);
    }

    public long elapsedMillis(long startedAt) {
        return recorder.elapsedMillis(startedAt);
    }
}
