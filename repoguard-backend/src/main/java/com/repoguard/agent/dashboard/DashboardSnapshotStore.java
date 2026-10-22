package com.repoguard.agent.dashboard;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.repoguard.agent.concurrency.AsyncExecutorProperties;
import com.repoguard.agent.tenancy.TenantContext;
import com.repoguard.agent.tenancy.TenantScopedKey;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class DashboardSnapshotStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(DashboardSnapshotStore.class);
    private final Cache<TenantScopedKey, State> states;
    // Only submitted/running durable refresh tasks live here; the executor bounds their number.
    private final Map<TenantScopedKey, Object> backgroundTasks = new ConcurrentHashMap<>();
    private final Executor executor;
    private final LongSupplier nanoClock;
    private final long maxStaleNanos;
    private final long freshNanos;
    private final long failureBackoffNanos;
    private final MeterRegistry registry;
    private final Counter discardedWrites;
    private final DistributionSummary servedAge;

    public DashboardSnapshotStore(Executor executor) {
        this(executor, 2048, Duration.ofSeconds(120), Duration.ofSeconds(5), Duration.ofSeconds(1),
            System::nanoTime, null);
    }

    @Autowired
    public DashboardSnapshotStore(
        @Qualifier(DashboardSnapshotExecutorConfig.DASHBOARD_SNAPSHOT_EXECUTOR) Executor executor,
        AsyncExecutorProperties properties, MeterRegistry registry
    ) {
        this(executor, properties.positive(properties.getDashboardSnapshotMaxEntries()),
            Duration.ofSeconds(properties.positive(properties.getDashboardSnapshotMaxStaleSeconds())),
            Duration.ofSeconds(Math.max(0, properties.getDashboardSnapshotFreshSeconds())),
            Duration.ofMillis(properties.positive(properties.getDashboardSnapshotFailureBackoffMillis())),
            System::nanoTime, registry);
    }

    DashboardSnapshotStore(Executor executor, int capacity, Duration maxStale,
        LongSupplier nanoClock, MeterRegistry registry) {
        this(executor, capacity, maxStale, Duration.ZERO, Duration.ZERO, nanoClock, registry);
    }

    DashboardSnapshotStore(Executor executor, int capacity, Duration maxStale, Duration fresh,
        Duration failureBackoff, LongSupplier nanoClock, MeterRegistry registry) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        if (capacity < 1 || maxStale.isNegative() || maxStale.isZero() || fresh.isNegative()
            || failureBackoff.isNegative()) {
            throw new IllegalArgumentException("Snapshot capacity and maximum stale age must be positive");
        }
        maxStaleNanos = maxStale.toNanos();
        freshNanos = Math.min(fresh.toNanos(), maxStaleNanos);
        failureBackoffNanos = Math.min(failureBackoff.toNanos(), Duration.ofSeconds(30).toNanos());
        this.registry = registry;
        states = Caffeine.newBuilder().maximumSize(capacity).expireAfterAccess(maxStale.multipliedBy(2))
            .ticker(nanoClock::getAsLong).build();
        discardedWrites = registry == null ? null : registry.counter("repoguard.dashboard.snapshot.discarded_write");
        servedAge = registry == null ? null : registry.summary("repoguard.dashboard.snapshot.served_age.seconds");
        if (registry != null) {
            registry.gauge("repoguard.dashboard.snapshot.retained_keys", states, Cache::estimatedSize);
        }
    }

    public <T> T getOrLoad(String key, Supplier<T> loader) {
        TenantScopedKey scopedKey = TenantScopedKey.current(key);
        Objects.requireNonNull(loader, "loader");
        for (int attempt = 0; attempt < 3; attempt++) {
            State state = states.get(scopedKey, ignored -> new State());
            Snapshot snapshot = state.snapshot;
            if (usable(snapshot)) {
                if (age(snapshot) >= freshNanos) refreshAsync(scopedKey, state, loader);
                return value(snapshot);
            }
            Snapshot loaded = loadOnce(scopedKey, state, loader);
            if (loaded != null) {
                return value(loaded);
            }
        }
        throw new IllegalStateException("Dashboard snapshot repeatedly invalidated while loading");
    }

    public void evict(String key) {
        states.invalidate(TenantScopedKey.current(key));
    }

    public void evictByPrefix(String prefix) {
        long tenantId = TenantContext.requireTenantId();
        states.asMap().keySet().removeIf(key -> key.belongsTo(tenantId)
            && key.businessKey() instanceof String businessKey && businessKey.startsWith(prefix));
    }

    public void executeAsync(String key, Runnable task) {
        TenantScopedKey scopedKey = TenantScopedKey.current(Objects.requireNonNull(key, "key"));
        Objects.requireNonNull(task, "task");
        Object token = new Object();
        if (backgroundTasks.putIfAbsent(scopedKey, token) != null) {
            return;
        }
        submit(scopedKey, task, () -> backgroundTasks.remove(scopedKey, token));
    }

    private <T> Snapshot loadOnce(TenantScopedKey key, State state, Supplier<T> loader) {
        synchronized (state) {
            if (states.getIfPresent(key) != state) {
                return null;
            }
            if (usable(state.snapshot)) {
                return state.snapshot;
            }
            long generation = ++state.generation;
            Snapshot loaded = new Snapshot(Objects.requireNonNull(loader.get(), "snapshot"), nanoClock.getAsLong());
            return commit(key, state, generation, loaded) ? loaded : null;
        }
    }

    private <T> void refreshAsync(TenantScopedKey key, State state, Supplier<T> loader) {
        if (state.failures > 0 && nanoClock.getAsLong() - state.retryAtNanos < 0) {
            refreshOutcome("backoff");
            return;
        }
        if (!state.refreshing.compareAndSet(false, true)) {
            refreshOutcome("coalesced");
            return;
        }
        final long generation;
        synchronized (state) {
            if (states.getIfPresent(key) != state) {
                state.refreshing.set(false);
                discard();
                return;
            }
            generation = ++state.generation;
        }
        refreshOutcome("submitted");
        submit(key, () -> {
            try {
                synchronized (state) {
                    if (states.getIfPresent(key) != state || state.generation != generation) {
                        discard();
                        return;
                    }
                }
                Snapshot loaded = new Snapshot(Objects.requireNonNull(loader.get(), "snapshot"), nanoClock.getAsLong());
                if (commit(key, state, generation, loaded)) refreshOutcome("success");
            } catch (RuntimeException ex) {
                refreshFailed(state);
                throw ex;
            }
        }, () -> state.refreshing.set(false), () -> refreshFailed(state));
    }

    private boolean commit(TenantScopedKey key, State state, long generation, Snapshot loaded) {
        synchronized (state) {
            boolean[] committed = {false};
            states.asMap().computeIfPresent(key, (ignored, current) -> {
                if (current == state && state.generation == generation) {
                    state.snapshot = loaded;
                    state.failures = 0;
                    committed[0] = true;
                }
                return current;
            });
            if (!committed[0]) {
                discard();
            }
            return committed[0];
        }
    }

    private void submit(TenantScopedKey key, Runnable task, Runnable release) {
        submit(key, task, release, () -> {});
    }

    private void submit(TenantScopedKey key, Runnable task, Runnable release, Runnable rejected) {
        try {
            executor.execute(TenantContext.wrap(() -> {
                try {
                    task.run();
                } catch (RuntimeException ex) {
                    LOGGER.warn("Dashboard async task failed key={}", key, ex);
                } finally {
                    release.run();
                }
            }));
        } catch (RuntimeException ex) {
            rejected.run();
            release.run();
            LOGGER.warn("Dashboard async task submission failed key={}", key, ex);
        }
    }

    private void refreshFailed(State state) {
        int failures = Math.min(6, state.failures + 1);
        long delay = Math.min(Duration.ofSeconds(30).toNanos(), failureBackoffNanos * (1L << (failures - 1)));
        long jitter = delay == 0 ? 0 : ThreadLocalRandom.current().nextLong(delay / 4 + 1);
        state.retryAtNanos = nanoClock.getAsLong() + Math.min(Duration.ofSeconds(30).toNanos(), delay + jitter);
        state.failures = failures;
        refreshOutcome("failed");
    }

    private void refreshOutcome(String outcome) {
        if (registry != null) registry.counter("repoguard.dashboard.snapshot.refresh", "outcome", outcome).increment();
    }

    private boolean usable(Snapshot snapshot) {
        return snapshot != null && age(snapshot) < maxStaleNanos;
    }

    private long age(Snapshot snapshot) {
        return Math.max(0L, nanoClock.getAsLong() - snapshot.loadedAtNanos());
    }

    @SuppressWarnings("unchecked")
    private <T> T value(Snapshot snapshot) {
        if (servedAge != null) {
            servedAge.record(age(snapshot) / 1_000_000_000d);
        }
        return (T) snapshot.value();
    }

    private void discard() {
        if (discardedWrites != null) {
            discardedWrites.increment();
        }
    }

    long retainedKeys() {
        states.cleanUp();
        return states.estimatedSize();
    }

    private static final class State {
        private volatile Snapshot snapshot;
        private long generation;
        private final AtomicBoolean refreshing = new AtomicBoolean();
        private volatile int failures;
        private volatile long retryAtNanos;
    }

    private record Snapshot(Object value, long loadedAtNanos) {
    }
}
