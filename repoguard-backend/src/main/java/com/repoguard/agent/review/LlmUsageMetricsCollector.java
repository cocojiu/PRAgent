package com.repoguard.agent.review;

import com.repoguard.agent.config.ReplicaRuntimeEnabled;
import com.repoguard.agent.config.RuntimeRoleContract;
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
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Fixed numeric telemetry for the existing log stack; accounting is read only by API replicas. */
@Component
@ReplicaRuntimeEnabled
public final class LlmUsageMetricsCollector {
    private static final Logger LOGGER = LoggerFactory.getLogger(LlmUsageMetricsCollector.class);
    private static final List<String> REASONS = List.of("llm_budget_request_invalid", "llm_budget_legacy_usage_unknown",
        "llm_budget_pricing_unknown", "llm_monthly_budget_exhausted", "llm_budget_duplicate_call",
        "llm_budget_store_unavailable", "llm_budget_tenant_context_missing", "llm_release_safety_revoked");
    private final JdbcTemplate jdbc;
    private final MeterRegistry registry;
    private final RuntimeRoleContract runtime;
    private final boolean accountingEnabled;
    private final Clock clock;
    private volatile Map<String, PendingSample> snapshot;
    private volatile long lastSuccess;

    @Autowired
    public LlmUsageMetricsCollector(JdbcTemplate jdbc, MeterRegistry registry, RuntimeRoleContract runtime,
        @Value("${repoguard.observability.llm-usage-metrics-enabled:true}") boolean accountingEnabled) {
        this(jdbc, registry, runtime, accountingEnabled, Clock.systemUTC());
    }
    LlmUsageMetricsCollector(JdbcTemplate jdbc, MeterRegistry registry, RuntimeRoleContract runtime,
        boolean accountingEnabled, Clock clock) {
        this.jdbc = jdbc; this.registry = registry; this.runtime = runtime;
        this.accountingEnabled = accountingEnabled; this.clock = clock;
        for (String status : List.of("RESERVED", "UNKNOWN", "LEGACY")) {
            Gauge.builder("repoguard.llm.usage.pending", this, value -> value.pending(status, false))
                .tag("status", status.toLowerCase(java.util.Locale.ROOT)).register(registry);
            Gauge.builder("repoguard.llm.usage.oldest.age", this, value -> value.pending(status, true))
                .baseUnit("seconds").tag("status", status.toLowerCase(java.util.Locale.ROOT)).register(registry);
        }
        Gauge.builder("repoguard.llm.usage.snapshot.last.success", this, value -> value.lastSuccess)
            .baseUnit("seconds").register(registry);
    }
    public void collect() {
        if (!runtime.apiEnabled() || !accountingEnabled) return;
        ResultSetExtractor<List<PendingSample>> extractor = rows -> {
            var values = new java.util.ArrayList<PendingSample>();
            while (rows.next()) {
                LocalDateTime oldest = rows.getObject("oldest", LocalDateTime.class);
                values.add(new PendingSample(rows.getString("status").toUpperCase(java.util.Locale.ROOT), rows.getLong("pending_count"),
                    oldest == null ? null : oldest.toInstant(ZoneOffset.UTC)));
            }
            return List.copyOf(values);
        };
        try {
            collected(jdbc.query(connection -> {
                var statement = connection.prepareStatement("select status,count(*) as pending_count,min(started_at) as oldest "
                    + "from llm_usage_call where " + LlmUsagePendingQuery.PENDING_PREDICATE + " group by status");
                statement.setQueryTimeout(3); return statement;
            }, extractor));
        } catch (org.springframework.dao.DataAccessException unavailable) {
            registry.counter("repoguard.llm.usage.snapshot.failed").increment();
            LOGGER.warn("LLM usage metrics collection failed category=store_unavailable");
        }
    }
    void collected(List<PendingSample> samples) {
        snapshot = samples.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(PendingSample::status, Function.identity()));
        lastSuccess = clock.instant().getEpochSecond();
    }
    @Scheduled(fixedDelay = 60000, initialDelay = 10000)
    public void sample() {
        collect(); emit("heartbeat", "all", 1);
        if (runtime.apiEnabled()) {
            emit("accounting_enabled", "all", accountingEnabled ? 1 : 0);
            for (String status : List.of("RESERVED", "UNKNOWN", "LEGACY")) {
                emit("llm_pending", status.toLowerCase(java.util.Locale.ROOT), pending(status, false));
                emit("llm_oldest_age", status.toLowerCase(java.util.Locale.ROOT), pending(status, true));
            }
            emit("accounting_snapshot_age", "all", Math.max(0, clock.instant().getEpochSecond() - lastSuccess));
        }
        for (String reason : REASONS) emit("budget_denied_total", reason,
            count("repoguard.llm.budget.denied", "reason", reason));
        emit("task_completed_total", "all", count("repoguard.review.task.completed"));
        emit("task_failed_total", "all", count("repoguard.review.task.failed"));
        emit("external_retry_total", "all", count("repoguard.external.call.retry"));
        emit("cache_hit_total", "all", count("repoguard.dashboard.cache.access", "result", "hit"));
        emit("cache_miss_total", "all", count("repoguard.dashboard.cache.access", "result", "miss"));
        var durations = registry.find("repoguard.review.task.duration").timers();
        emit("task_duration_count", "all", durations.stream().mapToDouble(Timer::count).sum());
        emit("task_duration_sum", "all", durations.stream().mapToDouble(value ->
            value.totalTime(java.util.concurrent.TimeUnit.SECONDS)).sum());
        var heaps = registry.find("jvm.memory.used").tag("area", "heap").gauges();
        if (!heaps.isEmpty()) emit("heap_bytes", "all", heaps.stream().mapToDouble(Gauge::value).sum());
    }
    private double pending(String status, boolean age) {
        Map<String, PendingSample> values = snapshot;
        if (values == null) return Double.NaN;
        PendingSample value = values.get(status);
        if (value == null) return 0;
        return age ? value.oldest() == null ? 0 : Math.max(0, Duration.between(value.oldest(), clock.instant()).toSeconds()) : value.count();
    }
    private double count(String name, String... tags) {
        return registry.find(name).tags(tags).counters().stream().mapToDouble(Counter::count).sum();
    }
    private void emit(String metric, String dimension, double value) {
        if (Double.isFinite(value)) LOGGER.info("operation=runtime_metric_snapshot metric={} role={} dimension={} value={}",
            metric, runtime.role().value(), dimension, value);
    }
    record PendingSample(String status, long count, Instant oldest) { }
}
