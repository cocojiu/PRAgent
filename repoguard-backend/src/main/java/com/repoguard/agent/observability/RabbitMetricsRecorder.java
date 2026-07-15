package com.repoguard.agent.observability;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class RabbitMetricsRecorder {

    private final MetricRecorderSupport metrics;
    private final ConcurrentMap<String, AtomicLong> gauges = new ConcurrentHashMap<>();

    public RabbitMetricsRecorder(MetricRecorderSupport metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    void publishFailed(String failurePhase, String reason) {
        metrics.counter(
            "repoguard.rabbit.publish.failed",
            "failure_phase", metrics.normalize(failurePhase),
            "reason", metrics.normalize(reason)
        ).increment();
    }

    void messageConsumed(Duration duration, String result, String failureCategory) {
        String[] tags = {
            "result", metrics.normalize(result),
            "failure_category", metrics.normalize(failureCategory)
        };
        metrics.timer("repoguard.rabbit.consume.duration", tags).record(metrics.nonNegative(duration));
        metrics.counter("repoguard.rabbit.consume", tags).increment();
    }

    void queueDepth(String queue, String state, long depth) {
        String normalizedQueue = metrics.normalize(queue);
        String normalizedState = metrics.normalize(state);
        String key = normalizedQueue + "|" + normalizedState;
        AtomicLong value = gauges.computeIfAbsent(key, ignored -> {
            AtomicLong gaugeValue = new AtomicLong();
            metrics.registerLongGauge(
                "repoguard.rabbit.queue.depth",
                gaugeValue,
                "queue", normalizedQueue,
                "state", normalizedState
            );
            return gaugeValue;
        });
        value.set(Math.max(0, depth));
    }

    void publishCompensationSucceeded(String failurePhase) {
        metrics.counter(
            "repoguard.rabbit.publish.compensation",
            "result", "success",
            "failure_phase", metrics.normalize(failurePhase),
            "reason", "none"
        ).increment();
    }

    void publishCompensationFailed(String failurePhase, String reason) {
        metrics.counter(
            "repoguard.rabbit.publish.compensation",
            "result", "failed",
            "failure_phase", metrics.normalize(failurePhase),
            "reason", metrics.normalize(reason)
        ).increment();
    }
}
