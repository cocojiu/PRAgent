package com.repoguard.agent.external;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import java.util.Objects;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public class ExternalCallResilience {

    private final CircuitBreaker githubCircuitBreaker;
    private final Retry githubRetry;
    private final RateLimiter githubRateLimiter;
    private final CircuitBreaker llmCircuitBreaker;
    private final Retry llmRetry;
    private final RateLimiter llmRateLimiter;
    private final Bulkhead llmBulkhead;
    private final int maxEndpointDomains;
    private final Map<String, CircuitBreaker> llmDomains = new LinkedHashMap<>();

    ExternalCallResilience(
        CircuitBreaker githubCircuitBreaker,
        Retry githubRetry,
        RateLimiter githubRateLimiter,
        CircuitBreaker llmCircuitBreaker,
        Retry llmRetry,
        RateLimiter llmRateLimiter,
        Bulkhead llmBulkhead
    ) {
        this(githubCircuitBreaker, githubRetry, githubRateLimiter, llmCircuitBreaker, llmRetry,
            llmRateLimiter, llmBulkhead, 64);
    }

    ExternalCallResilience(CircuitBreaker githubCircuitBreaker, Retry githubRetry, RateLimiter githubRateLimiter,
        CircuitBreaker llmCircuitBreaker, Retry llmRetry, RateLimiter llmRateLimiter, Bulkhead llmBulkhead,
        int maxEndpointDomains) {
        this.githubCircuitBreaker = Objects.requireNonNull(githubCircuitBreaker, "githubCircuitBreaker");
        this.githubRetry = Objects.requireNonNull(githubRetry, "githubRetry");
        this.githubRateLimiter = Objects.requireNonNull(githubRateLimiter, "githubRateLimiter");
        this.llmCircuitBreaker = Objects.requireNonNull(llmCircuitBreaker, "llmCircuitBreaker");
        this.llmRetry = Objects.requireNonNull(llmRetry, "llmRetry");
        this.llmRateLimiter = Objects.requireNonNull(llmRateLimiter, "llmRateLimiter");
        this.llmBulkhead = Objects.requireNonNull(llmBulkhead, "llmBulkhead");
        this.maxEndpointDomains = Math.max(1, Math.min(256, maxEndpointDomains));
    }

    public <T> T llm(String operation, String provider, String endpoint, LongSupplier remaining, Supplier<T> supplier) {
        return ExternalRetryTiming.withinBudget(Objects.requireNonNull(remaining, "remaining"), () -> executeWithBulkhead(
            "LLM", "llm", operation, supplier, endpointBreaker(provider, endpoint), llmRetry, llmRateLimiter, llmBulkhead));
    }

    private synchronized CircuitBreaker endpointBreaker(String provider, String endpoint) {
        URI uri = URI.create(endpoint);
        if (uri.getHost() == null || uri.getScheme() == null) throw ExternalRetryTiming.declined("endpoint_invalid");
        int port = uri.getPort() < 0 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
        String key = (provider == null ? "unknown" : provider.toLowerCase(Locale.ROOT)) + "|"
            + uri.getScheme().toLowerCase(Locale.ROOT) + "|" + uri.getHost().toLowerCase(Locale.ROOT) + "|" + port;
        CircuitBreaker existing = llmDomains.get(key);
        if (existing != null) return existing;
        // Never evict a live breaker: changing endpoint paths cannot reset an open fault domain.
        if (llmDomains.size() >= maxEndpointDomains) throw ExternalRetryTiming.declined("endpoint_registry_full");
        CircuitBreaker created = CircuitBreaker.of("repoguard-llm-domain-" + llmDomains.size(), llmCircuitBreaker.getCircuitBreakerConfig());
        llmDomains.put(key, created);
        return created;
    }

    synchronized int retainedEndpointDomains() { return llmDomains.size(); }

    public <T> T github(String operation, Supplier<T> supplier) {
        return execute(
            "GitHub",
            "github",
            operation,
            supplier,
            githubCircuitBreaker,
            githubRetry,
            githubRateLimiter
        );
    }

    public <T> T github(String operation, LongSupplier remaining, Supplier<T> supplier) {
        return ExternalRetryTiming.withinBudget("GitHub", Objects.requireNonNull(remaining, "remaining"),
            () -> github(operation, supplier));
    }

    public <T> T llm(String operation, Supplier<T> supplier) {
        return executeWithBulkhead(
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
        RateLimiter rateLimiter
    ) {
        Supplier<T> attempt = () -> {
            ExternalRetryTiming.requireRemaining();
            return supplier.get();
        };
        Supplier<T> limited = RateLimiter.decorateSupplier(rateLimiter, attempt);
        Supplier<T> decorated = () -> {
            ExternalRetryTiming.requireRemaining();
            return limited.get();
        };
        // Retry is inside the circuit breaker so one logical external call contributes
        // one final outcome instead of counting every retry attempt as a breaker failure.
        decorated = Retry.decorateSupplier(retry, decorated);
        decorated = CircuitBreaker.decorateSupplier(circuitBreaker, decorated);
        try {
            return decorated.get();
        } catch (RuntimeException ex) {
            RuntimeException mapped = mapInfrastructureException(system, categoryPrefix, operation, ex);
            throw mapped == null ? ex : mapped;
        }
    }

    private <T> T executeWithBulkhead(
        String system,
        String categoryPrefix,
        String operation,
        Supplier<T> supplier,
        CircuitBreaker circuitBreaker,
        Retry retry,
        RateLimiter rateLimiter,
        Bulkhead bulkhead
    ) {
        return execute(
            system,
            categoryPrefix,
            operation,
            Bulkhead.decorateSupplier(bulkhead, () -> {
                ExternalRetryTiming.requireRemaining();
                return supplier.get();
            }),
            circuitBreaker,
            retry,
            rateLimiter
        );
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
