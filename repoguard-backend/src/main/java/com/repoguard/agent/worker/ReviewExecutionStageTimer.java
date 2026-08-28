package com.repoguard.agent.worker;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.function.Supplier;
import com.repoguard.agent.review.execution.ReviewAttemptStageDurations;
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
        return record(stage, null, action);
    }

    <T> T record(String stage, ReviewAttemptStageDurations stages, Supplier<T> action) {
        LocalDateTime startedAt = clock.now();
        try {
            T result = action.get();
            Duration duration = Duration.between(startedAt, clock.now());
            metricsRecorder.recordStage(duration, stage, "success");
            if (stages != null) {
                stages.add(stage, duration);
            }
            return result;
        } catch (RuntimeException ex) {
            Duration duration = Duration.between(startedAt, clock.now());
            metricsRecorder.recordStage(duration, stage, "failed");
            if (stages != null) {
                stages.add(stage, duration);
            }
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
