package com.repoguard.agent.review.task;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.entity.ReviewTask;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
class ManualReviewCreationGate {

    private static final long CONCURRENT_CREATE_WAIT_SECONDS = 5;
    private static final long COMPLETED_CREATE_RETENTION_SECONDS = 5;

    private final ManualReviewIdempotencyCoordinator coordinator;

    ManualReviewCreationGate(ManualReviewIdempotencyCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "manualReviewIdempotencyCoordinator");
    }

    Claim claim(String idempotencyKey) {
        CompletableFuture<ReviewTask> ownerFuture = new CompletableFuture<>();
        CompletableFuture<ReviewTask> existingFuture = coordinator.registerOwner(idempotencyKey, ownerFuture);
        return new Claim(idempotencyKey, ownerFuture, existingFuture);
    }

    ReviewTask awaitExisting(Claim claim) {
        try {
            return claim.existingFuture().get(CONCURRENT_CREATE_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Interrupted while waiting for existing review task");
        } catch (ExecutionException ex) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Concurrent review task creation failed");
        } catch (TimeoutException ex) {
            coordinator.remove(claim.idempotencyKey(), claim.existingFuture());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "Timed out waiting for existing review task");
        }
    }

    void completeImmediately(Claim claim, ReviewTask task) {
        claim.ownerFuture().complete(task);
        coordinator.remove(claim.idempotencyKey(), claim.ownerFuture());
    }

    void completeAfterTransaction(Claim claim, ReviewTask task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            completeImmediately(claim, task);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                claim.ownerFuture().complete(task);
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    claim.ownerFuture().completeExceptionally(
                        new IllegalStateException("Manual review transaction rolled back")
                    );
                    coordinator.remove(claim.idempotencyKey(), claim.ownerFuture());
                    return;
                }
                coordinator.scheduleRemove(
                    claim.idempotencyKey(),
                    claim.ownerFuture(),
                    COMPLETED_CREATE_RETENTION_SECONDS,
                    TimeUnit.SECONDS
                );
            }
        });
    }

    void fail(Claim claim, RuntimeException failure) {
        claim.ownerFuture().completeExceptionally(failure);
        coordinator.remove(claim.idempotencyKey(), claim.ownerFuture());
    }

    record Claim(
        String idempotencyKey,
        CompletableFuture<ReviewTask> ownerFuture,
        CompletableFuture<ReviewTask> existingFuture
    ) {
        boolean owner() {
            return existingFuture == null;
        }
    }
}
