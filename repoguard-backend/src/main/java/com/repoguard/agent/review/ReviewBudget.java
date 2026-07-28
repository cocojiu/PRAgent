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
        return new ReviewBudget(startedAtNanos + budget.toNanos(), nanoClock);
    }

    static ReviewBudget startingAt(long startedAtNanos, Duration budget) {
        return startingAt(startedAtNanos, budget, System::nanoTime);
    }

    boolean exhausted() {
        return remainingNanos() <= 0;
    }

    /** Remaining budget in nanoseconds, never negative. */
    long remainingNanos() {
        return Math.max(0L, deadlineNanos - nanoClock.getAsLong());
    }
}
