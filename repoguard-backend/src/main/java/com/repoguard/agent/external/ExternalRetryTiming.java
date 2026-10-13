package com.repoguard.agent.external;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.web.client.RestClientResponseException;

/** Synchronous retry scheduling shares the caller's monotonic budget without retaining it. */
final class ExternalRetryTiming {
    private static final ThreadLocal<LongSupplier> REMAINING = new ThreadLocal<>();

    private ExternalRetryTiming() {
    }

    static <T> T withinBudget(LongSupplier remaining, Supplier<T> call) {
        LongSupplier previous = REMAINING.get();
        REMAINING.set(remaining);
        try {
            requireRemaining();
            return call.get();
        } finally {
            if (previous == null) REMAINING.remove(); else REMAINING.set(previous);
        }
    }

    static void requireRemaining() {
        LongSupplier remaining = REMAINING.get();
        if (Thread.currentThread().isInterrupted() || remaining != null && remaining.getAsLong() <= 0) {
            throw declined("deadline_exhausted");
        }
    }

    static long delay(int attempt, Throwable failure, ExternalCallResilienceProperties.Instance properties) {
        return delay(attempt, failure, properties, Clock.systemUTC(), ThreadLocalRandom.current().nextDouble());
    }

    static long delay(int attempt, Throwable failure, ExternalCallResilienceProperties.Instance properties,
        Clock clock, double random) {
        long maximum = Math.max(0, properties.getRetryMaxWaitMillis());
        double exponential = Math.max(0, properties.getRetryWaitMillis()) * Math.pow(2, Math.min(30, Math.max(0, attempt - 1)));
        long jittered = (long) (Math.min(maximum, exponential) * (0.5 + Math.max(0, Math.min(1, random))));
        long requested = retryAfterMillis(failure, clock);
        if (requested > maximum) throw declined("retry_after_exceeds_limit");
        long delay = Math.max(requested, Math.min(maximum, jittered));
        LongSupplier remaining = REMAINING.get();
        if (remaining != null && remaining.getAsLong() <= Duration.ofMillis(delay).toNanos()) {
            throw declined("deadline_exhausted");
        }
        requireRemaining();
        return delay;
    }

    private static long retryAfterMillis(Throwable failure, Clock clock) {
        String hint = failure instanceof RestClientResponseException http
            ? ExternalRetryAfterHint.fromHeaders(http.getResponseHeaders())
            : ExternalRetryAfterHint.retryAfter(failure == null ? null : failure.getMessage());
        if (hint == null || hint.isBlank()) return 0;
        try {
            if (hint.matches("[0-9]+")) return Math.multiplyExact(Long.parseLong(hint), 1000);
            long millis = Duration.between(clock.instant(), ZonedDateTime.parse(hint, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis();
            return Math.max(0, millis);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        } catch (IllegalArgumentException | java.time.DateTimeException invalid) {
            return 0;
        }
    }

    static boolean localDecline(Throwable failure) {
        if (failure instanceof io.github.resilience4j.ratelimiter.RequestNotPermitted
            || failure instanceof io.github.resilience4j.bulkhead.BulkheadFullException
            || failure instanceof io.github.resilience4j.circuitbreaker.CallNotPermittedException
            || failure instanceof LocalDeclineException) return true;
        return failure instanceof ExternalCallException external && (
            external.getCategory().startsWith("llm_budget_")
            || external.getCategory().equals("llm_release_safety_revoked")
            || external.getCategory().equals("llm_monthly_budget_exhausted"));
    }

    static ExternalCallException declined(String reason) {
        return new LocalDeclineException(reason);
    }

    private static final class LocalDeclineException extends ExternalCallException {
        private static final long serialVersionUID = 1L;
        private LocalDeclineException(String reason) {
            super("LLM", "llm_" + reason, false, null, "retry or request admission stopped locally", null);
        }
    }
}
