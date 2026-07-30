package com.repoguard.agent.observability;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.Cache;

public class ObservedCache implements Cache {

    private final Cache delegate;
    private final RepoGuardMetrics metrics;
    private final boolean observeAccess;

    public ObservedCache(Cache delegate, RepoGuardMetrics metrics, boolean observeAccess) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.observeAccess = observeAccess;
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public Object getNativeCache() {
        return delegate.getNativeCache();
    }

    @Override
    @Nullable
    public ValueWrapper get(Object key) {
        ValueWrapper value = delegate.get(key);
        recordAccess(value == null ? "miss" : "hit");
        return value;
    }

    @Override
    @Nullable
    public <T> T get(Object key, @Nullable Class<T> type) {
        T value = delegate.get(key, type);
        recordAccess(value == null ? "miss" : "hit");
        return value;
    }

    @Override
    @Nullable
    public <T> T get(Object key, Callable<T> valueLoader) {
        AtomicBoolean loaded = new AtomicBoolean(false);
        try {
            T value = delegate.get(key, () -> {
                loaded.set(true);
                return valueLoader.call();
            });
            recordAccess(loaded.get() ? "miss" : "hit");
            return value;
        } catch (RuntimeException ex) {
            recordAccess(loaded.get() ? "miss_failed" : "failed");
            throw ex;
        }
    }

    @Override
    @Nullable
    public CompletableFuture<?> retrieve(Object key) {
        CompletableFuture<?> value = delegate.retrieve(key);
        recordAccess(value == null ? "miss" : "hit");
        return value;
    }

    @Override
    @Nullable
    public <T> CompletableFuture<T> retrieve(Object key, Supplier<CompletableFuture<T>> valueLoader) {
        AtomicBoolean loaded = new AtomicBoolean(false);
        try {
            CompletableFuture<T> value = delegate.retrieve(key, () -> {
                loaded.set(true);
                return valueLoader.get();
            });
            recordAccess(loaded.get() ? "miss" : "hit");
            return value;
        } catch (RuntimeException ex) {
            recordAccess(loaded.get() ? "miss_failed" : "failed");
            throw ex;
        }
    }

    @Override
    public void put(Object key, @Nullable Object value) {
        delegate.put(key, value);
        recordOperation("put");
    }

    @Override
    @Nullable
    public ValueWrapper putIfAbsent(Object key, @Nullable Object value) {
        ValueWrapper existing = delegate.putIfAbsent(key, value);
        recordOperation(existing == null ? "put_if_absent" : "put_if_absent_skipped");
        return existing;
    }

    @Override
    public void evict(Object key) {
        delegate.evict(key);
        recordOperation("evict");
    }

    @Override
    public boolean evictIfPresent(Object key) {
        boolean evicted = delegate.evictIfPresent(key);
        recordOperation(evicted ? "evict_if_present" : "evict_if_present_miss");
        return evicted;
    }

    @Override
    public void clear() {
        delegate.clear();
        recordOperation("clear");
    }

    @Override
    public boolean invalidate() {
        boolean invalidated = delegate.invalidate();
        recordOperation(invalidated ? "invalidate" : "invalidate_miss");
        return invalidated;
    }

    private void recordAccess(String result) {
        if (observeAccess) {
            metrics.dashboardCacheAccess(getName(), result);
        }
    }

    private void recordOperation(String operation) {
        if (observeAccess) {
            metrics.dashboardCacheOperation(getName(), operation);
        }
    }
}
