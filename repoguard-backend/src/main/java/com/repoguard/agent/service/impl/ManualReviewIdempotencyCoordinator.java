package com.repoguard.agent.service.impl;

import com.repoguard.agent.entity.ReviewTask;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class ManualReviewIdempotencyCoordinator {

    private final ConcurrentMap<String, CompletableFuture<ReviewTask>> inFlightManualCreates = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor;

    @Autowired
    public ManualReviewIdempotencyCoordinator(
        @Qualifier(ManualReviewCleanupExecutorConfig.MANUAL_REVIEW_CLEANUP_EXECUTOR)
        ScheduledExecutorService cleanupExecutor
    ) {
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
}
