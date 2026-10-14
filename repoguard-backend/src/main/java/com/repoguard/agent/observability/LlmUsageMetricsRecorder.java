package com.repoguard.agent.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Fixed-cardinality accounting diagnostics; scraping never performs database work. */
@Component
public final class LlmUsageMetricsRecorder {
    private final MeterRegistry registry;
    private final Clock clock;
    private final AtomicReference<Map<String, PendingSample>> snapshot = new AtomicReference<>();
    private final AtomicLong lastSuccess = new AtomicLong();
    private final Counter collectionFailures;
    private final Timer queueWait;

    @Autowired
    public LlmUsageMetricsRecorder(MeterRegistry registry) { this(registry, Clock.systemUTC()); }

    LlmUsageMetricsRecorder(MeterRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
        for (String status : List.of("RESERVED", "UNKNOWN", "LEGACY")) {
            Gauge.builder("repoguard.llm.usage.pending", this, value -> value.count(status))
                .tag("status", status.toLowerCase(java.util.Locale.ROOT)).register(registry);
            Gauge.builder("repoguard.llm.usage.oldest.age", this, value -> value.age(status))
                .baseUnit("seconds").tag("status", status.toLowerCase(java.util.Locale.ROOT)).register(registry);
        }
        Gauge.builder("repoguard.llm.usage.snapshot.last.success", lastSuccess, AtomicLong::get)
            .baseUnit("seconds").register(registry);
        collectionFailures = registry.counter("repoguard.llm.usage.snapshot.failed");
        queueWait = Timer.builder("repoguard.review.queue.wait")
            .serviceLevelObjectives(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30),
                Duration.ofMinutes(1), Duration.ofMinutes(5))
            .register(registry);
    }

    public void queueWait(LocalDateTime queuedAt) {
        if (queuedAt == null) return;
        Duration elapsed = Duration.between(queuedAt.toInstant(ZoneOffset.UTC), clock.instant());
        queueWait.record(elapsed.isNegative() ? Duration.ZERO : elapsed);
    }

    public void collected(List<PendingSample> samples) {
        Map<String, PendingSample> values = samples.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
            PendingSample::status, java.util.function.Function.identity()));
        snapshot.set(values);
        lastSuccess.set(clock.instant().getEpochSecond());
    }

    public void collectionFailed() { collectionFailures.increment(); }

    private double count(String status) {
        Map<String, PendingSample> values = snapshot.get();
        if (values == null) return Double.NaN;
        PendingSample value = values.get(status);
        return value == null ? 0 : value.count();
    }

    private double age(String status) {
        Map<String, PendingSample> values = snapshot.get();
        if (values == null) return Double.NaN;
        PendingSample value = values.get(status);
        if (value == null || value.oldest() == null) return 0;
        return Math.max(0, Duration.between(value.oldest(), clock.instant()).toSeconds());
    }

    public record PendingSample(String status, long count, Instant oldest) { }
}
