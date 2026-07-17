package com.repoguard.agent.dashboard;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class DashboardSnapshotStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(DashboardSnapshotStore.class);

    private final Map<String, Object> snapshots = new ConcurrentHashMap<>();
    private final Map<String, Object> loadingLocks = new ConcurrentHashMap<>();
    private final Set<String> refreshingKeys = ConcurrentHashMap.newKeySet();
    private final Executor executor;

    public DashboardSnapshotStore(
        @Qualifier(DashboardSnapshotExecutorConfig.DASHBOARD_SNAPSHOT_EXECUTOR) Executor executor
    ) {
        this.executor = executor;
    }

    public <T> T getOrLoad(String key, Supplier<T> loader) {
        T snapshot = snapshot(key);
        if (snapshot == null) {
            return loadOnce(key, loader);
        }
        refreshAsync(key, loader);
        return snapshot;
    }

    public void evict(String key) {
        snapshots.remove(key);
        loadingLocks.remove(key);
        refreshingKeys.remove(key);
    }

    public void evictByPrefix(String prefix) {
        snapshots.keySet().removeIf(key -> key.startsWith(prefix));
        loadingLocks.keySet().removeIf(key -> key.startsWith(prefix));
        refreshingKeys.removeIf(key -> key.startsWith(prefix));
    }

    public void executeAsync(String key, Runnable task) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(task, "task must not be null");
        if (!refreshingKeys.add(key)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException ex) {
                    LOGGER.warn("Dashboard async task failed key={}", key, ex);
                } finally {
                    refreshingKeys.remove(key);
                }
            });
        } catch (RuntimeException ex) {
            refreshingKeys.remove(key);
            LOGGER.warn("Dashboard async task submission failed key={}", key, ex);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T snapshot(String key) {
        return (T) snapshots.get(key);
    }

    private <T> T loadAndStore(String key, Supplier<T> loader) {
        T value = loader.get();
        snapshots.put(key, value);
        return value;
    }

    private <T> T loadOnce(String key, Supplier<T> loader) {
        Object lock = loadingLocks.computeIfAbsent(key, ignored -> new Object());
        try {
            synchronized (lock) {
                T snapshot = snapshot(key);
                if (snapshot != null) {
                    return snapshot;
                }
                return loadAndStore(key, loader);
            }
        } finally {
            loadingLocks.remove(key, lock);
        }
    }

    private <T> void refreshAsync(String key, Supplier<T> loader) {
        executeAsync(key, () -> loadAndStore(key, loader));
    }
}
