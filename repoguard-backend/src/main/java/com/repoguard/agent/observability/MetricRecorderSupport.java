package com.repoguard.agent.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class MetricRecorderSupport {

    static final String UNKNOWN = "unknown";

    private final MeterRegistry meterRegistry;

    public MetricRecorderSupport(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
    }

    Counter counter(String name, String... tags) {
        return Counter.builder(name).tags(tags).register(meterRegistry);
    }

    Timer timer(String name, String... tags) {
        return Timer.builder(name).tags(tags).register(meterRegistry);
    }

    DistributionSummary summary(String name, String... tags) {
        return summaryWithUnit(name, "bytes", tags);
    }

    DistributionSummary summaryWithUnit(String name, String baseUnit, String... tags) {
        return DistributionSummary.builder(name)
            .baseUnit(baseUnit)
            .tags(tags)
            .register(meterRegistry);
    }

    void registerLongGauge(String name, AtomicLong value, String... tags) {
        Gauge.builder(name, value, AtomicLong::get)
            .tags(tags)
            .register(meterRegistry);
    }

    Duration nonNegative(Duration duration) {
        if (duration == null || duration.isNegative()) {
            return Duration.ZERO;
        }
        return duration;
    }

    String normalizeHttpMethod(String value) {
        if (!StringUtils.hasText(value)) {
            return UNKNOWN;
        }
        return value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z]+", "_");
    }

    String normalizeHttpStatus(int status) {
        return status <= 0 ? UNKNOWN : Integer.toString(status);
    }

    String normalizePath(String value) {
        if (!StringUtils.hasText(value)) {
            return UNKNOWN;
        }
        String normalized = value.trim().replaceAll("\\s+", "");
        return StringUtils.hasText(normalized) ? normalized.toLowerCase(Locale.ROOT) : UNKNOWN;
    }

    String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return UNKNOWN;
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "_");
    }
}
