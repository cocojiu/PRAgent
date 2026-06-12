package com.repoguard.agent.external;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import java.util.function.Supplier;

public class ExternalCallResilience {

    private final CircuitBreaker githubCircuitBreaker;
    private final Retry githubRetry;
    private final RateLimiter githubRateLimiter;
    private final CircuitBreaker llmCircuitBreaker;
    private final Retry llmRetry;
    private final RateLimiter llmRateLimiter;
    private final Bulkhead llmBulkhead;

    ExternalCallResilience(
        CircuitBreaker githubCircuitBreaker,
        Retry githubRetry,
        RateLimiter githubRateLimiter,
        CircuitBreaker llmCircuitBreaker,
        Retry llmRetry,
        RateLimiter llmRateLimiter,
        Bulkhead llmBulkhead
    ) {
        this.githubCircuitBreaker = githubCircuitBreaker;
        this.githubRetry = githubRetry;
        this.githubRateLimiter = githubRateLimiter;
        this.llmCircuitBreaker = llmCircuitBreaker;
        this.llmRetry = llmRetry;
        this.llmRateLimiter = llmRateLimiter;
        this.llmBulkhead = llmBulkhead;
    }

    public <T> T github(String operation, Supplier<T> supplier) {
        return execute(
            "GitHub",
            "github",
            operation,
            supplier,
            githubCircuitBreaker,
            githubRetry,
            githubRateLimiter,
            null
        );
    }

    public <T> T llm(String operation, Supplier<T> supplier) {
        return execute(
            "LLM",
            "llm",
            operation,
            supplier,
            llmCircuitBreaker,
            llmRetry,
            llmRateLimiter,
            llmBulkhead
        );
    }

    private <T> T execute(
        String system,
        String categoryPrefix,
        String operation,
        Supplier<T> supplier,
        CircuitBreaker circuitBreaker,
        Retry retry,
        RateLimiter rateLimiter,
        Bulkhead bulkhead
    ) {
        Supplier<T> decorated = supplier;
        decorated = CircuitBreaker.decorateSupplier(circuitBreaker, decorated);
        decorated = Retry.decorateSupplier(retry, decorated);
        decorated = RateLimiter.decorateSupplier(rateLimiter, decorated);
        if (bulkhead != null) {
            decorated = Bulkhead.decorateSupplier(bulkhead, decorated);
        }
        try {
            return decorated.get();
        } catch (RuntimeException ex) {
            RuntimeException mapped = mapInfrastructureException(system, categoryPrefix, operation, ex);
            throw mapped == null ? ex : mapped;
        }
    }

    private RuntimeException mapInfrastructureException(
        String system,
        String categoryPrefix,
        String operation,
        RuntimeException ex
    ) {
        if (ex instanceof ExternalCallException) {
            return ex;
        }
        if (ex instanceof CallNotPermittedException) {
            return failure(system, categoryPrefix + "_circuit_open", operation, false, ex);
        }
        if (ex instanceof RequestNotPermitted) {
            return failure(system, categoryPrefix + "_rate_limited", operation, true, ex);
        }
        if (ex instanceof BulkheadFullException) {
            return failure(system, categoryPrefix + "_bulkhead_full", operation, true, ex);
        }
        return null;
    }

    private ExternalCallException failure(
        String system,
        String category,
        String operation,
        boolean retryable,
        RuntimeException ex
    ) {
        return new ExternalCallException(
            system,
            category,
            retryable,
            null,
            "operation=" + operation + " " + ex.getMessage(),
            ex
        );
    }
}
