package com.repoguard.agent.review;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Applies the chunk-count and in-flight capacity limits while preserving the
 * input order of review outcomes.
 */
final class LlmChunkReviewScheduler {

    static final String BUDGET_EXHAUSTED_CATEGORY = "budget_exhausted";
    static final String CHUNK_LIMIT_EXCEEDED_CATEGORY = "chunk_limit_exceeded";
    static final String EXECUTOR_REJECTED_CATEGORY = "executor_rejected";

    private final Executor chunkExecutor;
    private final int maxTotalChunks;
    private final int maxInFlightChunks;

    LlmChunkReviewScheduler(Executor chunkExecutor, int maxTotalChunks, int maxInFlightChunks) {
        this.chunkExecutor = Objects.requireNonNull(chunkExecutor, "chunkExecutor");
        this.maxTotalChunks = requirePositive(maxTotalChunks, "maxTotalChunks");
        this.maxInFlightChunks = requirePositive(maxInFlightChunks, "maxInFlightChunks");
    }

    <T> List<T> schedule(
        List<PullRequestDiffChunk> chunks,
        ReviewBudget budget,
        ChunkReviewer<T> reviewer,
        ChunkFallback<T> fallback
    ) {
        Objects.requireNonNull(chunks, "chunks");
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(reviewer, "reviewer");
        Objects.requireNonNull(fallback, "fallback");

        List<T> outcomes = new ArrayList<>(Collections.nCopies(chunks.size(), null));
        Deque<PendingChunk<T>> inFlight = new ArrayDeque<>(maxInFlightChunks);
        int llmChunkLimit = Math.min(chunks.size(), maxTotalChunks);
        int nextChunkIndex = 0;

        try {
            nextChunkIndex = fillWindow(
                chunks,
                budget,
                reviewer,
                fallback,
                outcomes,
                inFlight,
                nextChunkIndex,
                llmChunkLimit
            );
            while (!inFlight.isEmpty()) {
                PendingChunk<T> pending = inFlight.removeFirst();
                AwaitedChunk<T> awaited = await(pending, budget, fallback);
                outcomes.set(pending.index(), awaited.outcome());
                if (awaited.budgetExhausted()) {
                    harvestCompletedAndCancelRemaining(inFlight, outcomes, fallback);
                    break;
                }
                nextChunkIndex = fillWindow(
                    chunks,
                    budget,
                    reviewer,
                    fallback,
                    outcomes,
                    inFlight,
                    nextChunkIndex,
                    llmChunkLimit
                );
            }
        } catch (RuntimeException ex) {
            cancelRemaining(inFlight);
            throw ex;
        }

        fillFallbacks(
            chunks,
            outcomes,
            nextChunkIndex,
            llmChunkLimit,
            BUDGET_EXHAUSTED_CATEGORY,
            fallback
        );
        fillFallbacks(
            chunks,
            outcomes,
            llmChunkLimit,
            chunks.size(),
            CHUNK_LIMIT_EXCEEDED_CATEGORY,
            fallback
        );
        return outcomes;
    }

    private <T> int fillWindow(
        List<PullRequestDiffChunk> chunks,
        ReviewBudget budget,
        ChunkReviewer<T> reviewer,
        ChunkFallback<T> fallback,
        List<T> outcomes,
        Deque<PendingChunk<T>> inFlight,
        int nextChunkIndex,
        int llmChunkLimit
    ) {
        int next = nextChunkIndex;
        while (next < llmChunkLimit && inFlight.size() < maxInFlightChunks && !budget.exhausted()) {
            PullRequestDiffChunk chunk = chunks.get(next);
            int outcomeIndex = next;
            FutureTask<T> future = new FutureTask<>(() -> reviewer.review(chunk));
            try {
                chunkExecutor.execute(future);
                inFlight.addLast(new PendingChunk<>(outcomeIndex, chunk, future));
            } catch (RejectedExecutionException ex) {
                outcomes.set(outcomeIndex, fallback.fallback(chunk, EXECUTOR_REJECTED_CATEGORY, ex));
            }
            next++;
        }
        return next;
    }

    private <T> AwaitedChunk<T> await(
        PendingChunk<T> pending,
        ReviewBudget budget,
        ChunkFallback<T> fallback
    ) {
        FutureTask<T> future = pending.future();
        try {
            long remainingNanos = budget.remainingNanos();
            if (remainingNanos <= 0 && !future.isDone()) {
                future.cancel(true);
                return new AwaitedChunk<>(
                    fallback.fallback(pending.chunk(), BUDGET_EXHAUSTED_CATEGORY, null),
                    true
                );
            }
            T outcome = remainingNanos <= 0
                ? future.get()
                : future.get(remainingNanos, TimeUnit.NANOSECONDS);
            return new AwaitedChunk<>(outcome, budget.exhausted());
        } catch (TimeoutException ex) {
            future.cancel(true);
            return new AwaitedChunk<>(
                fallback.fallback(pending.chunk(), BUDGET_EXHAUSTED_CATEGORY, null),
                true
            );
        } catch (CancellationException ex) {
            return new AwaitedChunk<>(
                fallback.fallback(pending.chunk(), BUDGET_EXHAUSTED_CATEGORY, null),
                budget.exhausted()
            );
        } catch (ExecutionException ex) {
            throw ex.getCause() instanceof RuntimeException cause ? cause : new CompletionException(ex.getCause());
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new CompletionException(ex);
        }
    }

    private <T> void harvestCompletedAndCancelRemaining(
        Deque<PendingChunk<T>> inFlight,
        List<T> outcomes,
        ChunkFallback<T> fallback
    ) {
        while (!inFlight.isEmpty()) {
            PendingChunk<T> pending = inFlight.removeFirst();
            FutureTask<T> future = pending.future();
            if (!future.isDone()) {
                boolean cancelled = future.cancel(true);
                if (cancelled) {
                    outcomes.set(
                        pending.index(),
                        fallback.fallback(pending.chunk(), BUDGET_EXHAUSTED_CATEGORY, null)
                    );
                    continue;
                }
            }
            outcomes.set(pending.index(), completedOutcome(pending, fallback));
        }
    }

    private <T> T completedOutcome(PendingChunk<T> pending, ChunkFallback<T> fallback) {
        try {
            return pending.future().get();
        } catch (CancellationException ex) {
            return fallback.fallback(pending.chunk(), BUDGET_EXHAUSTED_CATEGORY, null);
        } catch (ExecutionException ex) {
            throw ex.getCause() instanceof RuntimeException cause ? cause : new CompletionException(ex.getCause());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CompletionException(ex);
        }
    }

    private <T> void fillFallbacks(
        List<PullRequestDiffChunk> chunks,
        List<T> outcomes,
        int fromInclusive,
        int toExclusive,
        String category,
        ChunkFallback<T> fallback
    ) {
        for (int index = fromInclusive; index < toExclusive; index++) {
            if (outcomes.get(index) == null) {
                outcomes.set(index, fallback.fallback(chunks.get(index), category, null));
            }
        }
    }

    private <T> void cancelRemaining(Deque<PendingChunk<T>> inFlight) {
        for (PendingChunk<T> pending : inFlight) {
            pending.future().cancel(true);
        }
        inFlight.clear();
    }

    private static int requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    @FunctionalInterface
    interface ChunkReviewer<T> {
        T review(PullRequestDiffChunk chunk);
    }

    @FunctionalInterface
    interface ChunkFallback<T> {
        T fallback(PullRequestDiffChunk chunk, String category, RuntimeException failure);
    }

    private record PendingChunk<T>(int index, PullRequestDiffChunk chunk, FutureTask<T> future) {
    }

    private record AwaitedChunk<T>(T outcome, boolean budgetExhausted) {
    }
}
