package com.repoguard.agent.worker;

import com.repoguard.agent.messaging.RabbitConsumeMetricsRecorder;
import com.repoguard.agent.messaging.RabbitConsumeMetricsRecorderFactory;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class ReviewTaskWorkerMetricsRecorder {

    private final RabbitConsumeMetricsRecorder recorder;
    private final Timer queueWait;
    private final ReviewTaskWorkerClock clock;

    ReviewTaskWorkerMetricsRecorder(
        RabbitConsumeMetricsRecorderFactory recorderFactory,
        ReviewTaskWorkerClock clock
    ) {
        this(recorderFactory, clock, null);
    }

    @Autowired
    ReviewTaskWorkerMetricsRecorder(RabbitConsumeMetricsRecorderFactory recorderFactory,
        ReviewTaskWorkerClock clock, MeterRegistry registry) {
        this.recorder = Objects.requireNonNull(recorderFactory, "recorderFactory")
            .create(Objects.requireNonNull(clock, "clock")::nanoTime);
        this.clock = clock;
        queueWait = registry == null ? null : Timer.builder("repoguard.review.queue.wait")
            .serviceLevelObjectives(java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(5),
                java.time.Duration.ofSeconds(30), java.time.Duration.ofMinutes(1), java.time.Duration.ofMinutes(5)).register(registry);
    }

    void recordQueueWait(LocalDateTime queuedAt) {
        if (queuedAt == null) return;
        long millis = Math.max(0, java.time.Duration.between(queuedAt.toInstant(java.time.ZoneOffset.UTC), clock.utcNow()).toMillis());
        if (queueWait != null) queueWait.record(java.time.Duration.ofMillis(millis));
        org.slf4j.LoggerFactory.getLogger(ReviewTaskWorkerMetricsRecorder.class)
            .info("operation=review_queue_wait value={}", millis / 1000d);
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
