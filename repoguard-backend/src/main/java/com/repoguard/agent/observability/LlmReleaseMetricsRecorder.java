package com.repoguard.agent.observability;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/** Micrometer gauges and counters for bounded release-key runtime telemetry. */
@Component
public class LlmReleaseMetricsRecorder {

    private final MetricRecorderSupport metrics;
    private final ConcurrentMap<String, AtomicLong> gauges = new ConcurrentHashMap<>();

    public LlmReleaseMetricsRecorder(MetricRecorderSupport metrics) {
        this.metrics = metrics;
    }

    public void snapshot(String releaseKey, String provider, String model, long sampleCount, long totalTokens,
        double totalCost, long p95LatencyMs, double parseFailureRate, double fallbackRate, String alertState) {
        String[] tags = tags(releaseKey, provider, model, alertState);
        setGauge("sample_count", sampleCount, tags);
        setGauge("total_tokens", totalTokens, tags);
        setGauge("total_cost_micros", Math.max(0L, Math.round(totalCost * 1_000_000d)), tags);
        setGauge("p95_latency_ms", p95LatencyMs, tags);
        setGauge("parse_failure_rate_micros", Math.max(0L, Math.round(parseFailureRate * 1_000_000d)), tags);
        setGauge("fallback_rate_micros", Math.max(0L, Math.round(fallbackRate * 1_000_000d)), tags);
        metrics.counter("repoguard.llm.release.snapshot", tags).increment();
    }

    public void alert(String releaseKey, String code, String action) {
        metrics.counter("repoguard.llm.release.alert",
            "release_key", metrics.normalize(releaseKey),
            "code", metrics.normalize(code),
            "action", metrics.normalize(action)
        ).increment();
    }

    private void setGauge(String metric, long value, String[] tags) {
        String key = metric + '|' + String.join("|", tags);
        AtomicLong holder = gauges.computeIfAbsent(key, ignored -> {
            AtomicLong created = new AtomicLong();
            metrics.registerLongGauge("repoguard.llm.release." + metric, created, tags);
            return created;
        });
        holder.set(Math.max(0L, value));
    }

    private String[] tags(String releaseKey, String provider, String model, String alertState) {
        return new String[] {
            "release_key", metrics.normalize(releaseKey),
            "provider", metrics.normalize(provider),
            "model", metrics.normalize(model),
            "alert_state", metrics.normalize(alertState)
        };
    }
}
