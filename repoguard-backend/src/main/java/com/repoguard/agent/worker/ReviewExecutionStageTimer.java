package com.repoguard.agent.worker;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

@Component
class ReviewExecutionStageTimer {

    private final ReviewExecutionClock clock;
    private final ReviewExecutionMetricsRecorder metricsRecorder;

    ReviewExecutionStageTimer(ReviewExecutionClock clock, ReviewExecutionMetricsRecorder metricsRecorder) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metricsRecorder = Objects.requireNonNull(metricsRecorder, "metricsRecorder");
    }

    <T> T record(String stage, Supplier<T> action) {
        LocalDateTime startedAt = clock.now();
        try {
            T result = action.get();
            metricsRecorder.recordStage(Duration.between(startedAt, clock.now()), stage, "success");
            return result;
        } catch (RuntimeException ex) {
            metricsRecorder.recordStage(Duration.between(startedAt, clock.now()), stage, "failed");
            throw ex;
        }
    }

    void record(String stage, Runnable action) {
        record(stage, () -> {
            action.run();
            return null;
        });
    }
}
