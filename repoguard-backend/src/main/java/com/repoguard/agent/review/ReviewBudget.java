package com.repoguard.agent.review;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Wall-clock budget for one LLM review pipeline run.
 *
 * <p>Individual LLM calls already carry an HTTP timeout, but a chunked review
 * issues many of them, so the pipeline as a whole had no upper bound. Without
 * this the RabbitMQ consumer timeout and the recovery staleness threshold could
 * both fire on the same task, re-running work that was already in flight.
 *
 * <p>The budget bounds when new LLM calls may <em>start</em>; a call already in
 * flight still runs to its own timeout, so real elapsed time can exceed the
 * budget by at most one chunk's call sequence.
 */
final class ReviewBudget {

    private final long deadlineNanos;
    private final LongSupplier nanoClock;

    private ReviewBudget(long deadlineNanos, LongSupplier nanoClock) {
        this.deadlineNanos = deadlineNanos;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    static ReviewBudget startingAt(long startedAtNanos, Duration budget, LongSupplier nanoClock) {
        Objects.requireNonNull(budget, "budget");
        return new ReviewBudget(saturatingAdd(startedAtNanos, budget.toNanos()), nanoClock);
    }

    static ReviewBudget startingAt(long startedAtNanos, Duration budget) {
        return startingAt(startedAtNanos, budget, System::nanoTime);
    }

    static ReviewBudget boundedBy(
        long startedAtNanos,
        Duration pipelineBudget,
        ReviewDeadline executionDeadline
    ) {
        Objects.requireNonNull(pipelineBudget, "pipelineBudget");
        if (executionDeadline == null) {
            return startingAt(startedAtNanos, pipelineBudget);
        }
        long pipelineNanos = pipelineBudget.toNanos();
        long pipelineDeadline = saturatingAdd(startedAtNanos, pipelineNanos);
        return new ReviewBudget(
            Math.min(pipelineDeadline, executionDeadline.deadlineNanos()),
            System::nanoTime
        );
    }

    boolean exhausted() {
        return remainingNanos() <= 0;
    }

    /** Remaining budget in nanoseconds, never negative. */
    long remainingNanos() {
        if (deadlineNanos == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        long now = nanoClock.getAsLong();
        if (now >= deadlineNanos) {
            return 0L;
        }
        long remaining = deadlineNanos - now;
        return remaining < 0L ? Long.MAX_VALUE : remaining;
    }

    private static long saturatingAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ex) {
            return Long.MAX_VALUE;
        }
    }
}
