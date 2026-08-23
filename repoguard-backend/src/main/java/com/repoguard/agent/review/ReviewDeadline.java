package com.repoguard.agent.review;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Monotonic, wall-clock budget shared by every stage of one review execution. */
public final class ReviewDeadline {

    private final long startedAtNanos;
    private final long deadlineNanos;
    private final LongSupplier nanoClock;

    private ReviewDeadline(long startedAtNanos, long deadlineNanos, LongSupplier nanoClock) {
        this.startedAtNanos = startedAtNanos;
        this.deadlineNanos = deadlineNanos;
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    public static ReviewDeadline startingNow(Duration budget) {
        long startedAt = System.nanoTime();
        return startingAt(startedAt, budget, System::nanoTime);
    }

    public static ReviewDeadline startingAt(
        long startedAtNanos,
        Duration budget,
        LongSupplier nanoClock
    ) {
        Objects.requireNonNull(budget, "budget");
        if (budget.isZero() || budget.isNegative()) {
            throw new IllegalArgumentException("Review execution budget must be positive");
        }
        long budgetNanos = budget.toNanos();
        long deadline = saturatingAdd(startedAtNanos, budgetNanos);
        return new ReviewDeadline(startedAtNanos, deadline, nanoClock);
    }

    public static ReviewDeadline unlimited() {
        return new ReviewDeadline(System.nanoTime(), Long.MAX_VALUE, System::nanoTime);
    }

    public long startedAtNanos() {
        return startedAtNanos;
    }

    long deadlineNanos() {
        return deadlineNanos;
    }

    public long remainingNanos() {
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

    public boolean exhausted() {
        return remainingNanos() <= 0;
    }

    public void requireRemaining(String stage) {
        if (exhausted()) {
            throw new ReviewBudgetExceededException(stage);
        }
    }

    public ReviewDeadline reserving(Duration reserve) {
        Objects.requireNonNull(reserve, "reserve");
        if (reserve.isNegative()) {
            throw new IllegalArgumentException("Review deadline reserve must not be negative");
        }
        long adjusted = saturatingSubtract(deadlineNanos, reserve.toNanos());
        if (adjusted <= startedAtNanos) {
            throw new IllegalArgumentException("Review deadline reserve must be smaller than the budget");
        }
        return new ReviewDeadline(startedAtNanos, adjusted, nanoClock);
    }

    public ReviewDeadline extending(Duration extension) {
        Objects.requireNonNull(extension, "extension");
        if (extension.isNegative()) {
            throw new IllegalArgumentException("Review deadline extension must not be negative");
        }
        return new ReviewDeadline(startedAtNanos, saturatingAdd(deadlineNanos, extension.toNanos()), nanoClock);
    }

    private static long saturatingAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException ex) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatingSubtract(long left, long right) {
        try {
            return Math.subtractExact(left, right);
        } catch (ArithmeticException ex) {
            return Long.MIN_VALUE;
        }
    }
}
