package com.repoguard.agent.dashboard;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DashboardSnapshotStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(DashboardSnapshotStore.class);

    private final Map<String, Object> snapshots = new ConcurrentHashMap<>();
    private final Set<String> refreshingKeys = ConcurrentHashMap.newKeySet();
    private final Executor executor;

    public DashboardSnapshotStore() {
        this(Executors.newSingleThreadExecutor(new DashboardSnapshotThreadFactory()));
    }

    public DashboardSnapshotStore(Executor executor) {
        this.executor = executor;
    }

    public <T> T getOrLoad(String key, Supplier<T> loader) {
        T snapshot = snapshot(key);
        if (snapshot == null) {
            return loadAndStore(key, loader);
        }
        refreshAsync(key, loader);
        return snapshot;
    }

    @PreDestroy
    public void shutdown() {
        if (executor instanceof ExecutorService executorService) {
            executorService.shutdown();
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

    private <T> void refreshAsync(String key, Supplier<T> loader) {
        if (!refreshingKeys.add(key)) {
            return;
        }
        executor.execute(() -> {
            try {
                loadAndStore(key, loader);
            } catch (RuntimeException ex) {
                LOGGER.warn("Dashboard snapshot refresh failed key={}", key, ex);
            } finally {
                refreshingKeys.remove(key);
            }
        });
    }

    private static final class DashboardSnapshotThreadFactory implements java.util.concurrent.ThreadFactory {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "repoguard-dashboard-snapshot");
            thread.setDaemon(true);
            return thread;
        }
    }
}
