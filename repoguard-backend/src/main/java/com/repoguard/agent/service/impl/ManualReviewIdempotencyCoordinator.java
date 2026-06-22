package com.repoguard.agent.service.impl;

import com.repoguard.agent.entity.ReviewTask;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ManualReviewIdempotencyCoordinator {

    private final ConcurrentMap<String, CompletableFuture<ReviewTask>> inFlightManualCreates = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor;

    @Autowired
    public ManualReviewIdempotencyCoordinator() {
        this(Executors.newSingleThreadScheduledExecutor(new ManualReviewCleanupThreadFactory()));
    }

    ManualReviewIdempotencyCoordinator(ScheduledExecutorService cleanupExecutor) {
        this.cleanupExecutor = cleanupExecutor;
    }

    public CompletableFuture<ReviewTask> registerOwner(String idempotencyKey, CompletableFuture<ReviewTask> ownerFuture) {
        return inFlightManualCreates.putIfAbsent(idempotencyKey, ownerFuture);
    }

    public void remove(String idempotencyKey, CompletableFuture<ReviewTask> future) {
        inFlightManualCreates.remove(idempotencyKey, future);
    }

    public void scheduleRemove(String idempotencyKey, CompletableFuture<ReviewTask> future, long delay, TimeUnit unit) {
        cleanupExecutor.schedule(() -> remove(idempotencyKey, future), delay, unit);
    }

    @PreDestroy
    public void shutdown() {
        cleanupExecutor.shutdown();
    }

    private static class ManualReviewCleanupThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "manual-review-cleanup-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
