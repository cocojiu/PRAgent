package com.repoguard.agent.dashboard;

import com.repoguard.agent.tenancy.TenantContext;
import com.repoguard.agent.tenancy.TenantScopedKey;
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

    private final Map<TenantScopedKey, Object> snapshots = new ConcurrentHashMap<>();
    private final Map<TenantScopedKey, Object> loadingLocks = new ConcurrentHashMap<>();
    private final Set<TenantScopedKey> refreshingKeys = ConcurrentHashMap.newKeySet();
    private final Executor executor;

    public DashboardSnapshotStore(
        @Qualifier(DashboardSnapshotExecutorConfig.DASHBOARD_SNAPSHOT_EXECUTOR) Executor executor
    ) {
        this.executor = executor;
    }

    public <T> T getOrLoad(String key, Supplier<T> loader) {
        TenantScopedKey scopedKey = TenantScopedKey.current(key);
        T snapshot = snapshot(scopedKey);
        if (snapshot == null) {
            return loadOnce(scopedKey, loader);
        }
        refreshAsync(scopedKey, loader);
        return snapshot;
    }

    public void evict(String key) {
        TenantScopedKey scopedKey = TenantScopedKey.current(key);
        snapshots.remove(scopedKey);
        loadingLocks.remove(scopedKey);
        refreshingKeys.remove(scopedKey);
    }

    public void evictByPrefix(String prefix) {
        long tenantId = TenantContext.currentTenantIdOrDefault();
        snapshots.keySet().removeIf(key -> matchesPrefix(key, tenantId, prefix));
        loadingLocks.keySet().removeIf(key -> matchesPrefix(key, tenantId, prefix));
        refreshingKeys.removeIf(key -> matchesPrefix(key, tenantId, prefix));
    }

    public void executeAsync(String key, Runnable task) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(task, "task must not be null");
        executeAsync(TenantScopedKey.current(key), task);
    }

    private void executeAsync(TenantScopedKey key, Runnable task) {
        if (!refreshingKeys.add(key)) {
            return;
        }
        try {
            executor.execute(TenantContext.wrap(() -> {
                try {
                    task.run();
                } catch (RuntimeException ex) {
                    LOGGER.warn("Dashboard async task failed key={}", key, ex);
                } finally {
                    refreshingKeys.remove(key);
                }
            }));
        } catch (RuntimeException ex) {
            refreshingKeys.remove(key);
            LOGGER.warn("Dashboard async task submission failed key={}", key, ex);
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T snapshot(TenantScopedKey key) {
        return (T) snapshots.get(key);
    }

    private <T> T loadAndStore(TenantScopedKey key, Supplier<T> loader) {
        T value = loader.get();
        snapshots.put(key, value);
        return value;
    }

    private <T> T loadOnce(TenantScopedKey key, Supplier<T> loader) {
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

    private <T> void refreshAsync(TenantScopedKey key, Supplier<T> loader) {
        executeAsync(key, () -> loadAndStore(key, loader));
    }

    private boolean matchesPrefix(TenantScopedKey key, long tenantId, String prefix) {
        return key.belongsTo(tenantId)
            && key.businessKey() instanceof String businessKey
            && businessKey.startsWith(prefix);
    }
}
