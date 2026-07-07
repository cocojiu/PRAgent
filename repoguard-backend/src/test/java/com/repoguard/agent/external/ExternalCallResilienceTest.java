package com.repoguard.agent.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ExternalCallResilienceTest {

    @Test
    void githubCircuitOpenUsesExistingExternalCallClassification() {
        CircuitBreaker githubCircuitBreaker = CircuitBreaker.of("github-test", CircuitBreakerConfig.ofDefaults());
        githubCircuitBreaker.transitionToOpenState();
        ExternalCallResilience resilience = resilience(githubCircuitBreaker, circuitBreaker("llm-test"));

        assertThatThrownBy(() -> resilience.github("fetch_pull_request_diff", () -> "ok"))
            .isInstanceOfSatisfying(ExternalCallException.class, ex -> {
                assertThat(ex.getSystem()).isEqualTo("GitHub");
                assertThat(ex.getCategory()).isEqualTo("github_circuit_open");
                assertThat(ex.isRetryable()).isFalse();
            });
    }

    @Test
    void llmCircuitOpenUsesExistingExternalCallClassification() {
        CircuitBreaker llmCircuitBreaker = CircuitBreaker.of("llm-test", CircuitBreakerConfig.ofDefaults());
        llmCircuitBreaker.transitionToOpenState();
        ExternalCallResilience resilience = resilience(circuitBreaker("github-test"), llmCircuitBreaker);

        assertThatThrownBy(() -> resilience.llm("chat_completions", () -> "ok"))
            .isInstanceOfSatisfying(ExternalCallException.class, ex -> {
                assertThat(ex.getSystem()).isEqualTo("LLM");
                assertThat(ex.getCategory()).isEqualTo("llm_circuit_open");
                assertThat(ex.isRetryable()).isFalse();
            });
    }

    @Test
    void constructorRejectsMissingLlmBulkhead() {
        assertThatThrownBy(() -> new ExternalCallResilience(
            circuitBreaker("github-test"),
            retry("github-test"),
            rateLimiter("github-test"),
            circuitBreaker("llm-test"),
            retry("llm-test"),
            rateLimiter("llm-test"),
            null
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("llmBulkhead");
    }

    @Test
    void llmBulkheadFullUsesStableExternalCallClassification() {
        Bulkhead bulkhead = Bulkhead.of("llm-test", BulkheadConfig.custom().maxConcurrentCalls(1).build());
        bulkhead.acquirePermission();
        ExternalCallResilience resilience = resilience(circuitBreaker("github-test"), circuitBreaker("llm-test"), bulkhead);

        assertThatThrownBy(() -> resilience.llm("chat_completions", () -> "ok"))
            .isInstanceOfSatisfying(ExternalCallException.class, ex -> {
                assertThat(ex.getSystem()).isEqualTo("LLM");
                assertThat(ex.getCategory()).isEqualTo("llm_bulkhead_full");
                assertThat(ex.isRetryable()).isTrue();
            });

        bulkhead.releasePermission();
    }

    private ExternalCallResilience resilience(CircuitBreaker githubCircuitBreaker, CircuitBreaker llmCircuitBreaker) {
        return resilience(
            githubCircuitBreaker,
            llmCircuitBreaker,
            Bulkhead.of("llm-test", BulkheadConfig.custom().maxConcurrentCalls(1).build())
        );
    }

    private ExternalCallResilience resilience(
        CircuitBreaker githubCircuitBreaker,
        CircuitBreaker llmCircuitBreaker,
        Bulkhead llmBulkhead
    ) {
        return new ExternalCallResilience(
            githubCircuitBreaker,
            retry("github-test"),
            rateLimiter("github-test"),
            llmCircuitBreaker,
            retry("llm-test"),
            rateLimiter("llm-test"),
            llmBulkhead
        );
    }

    private CircuitBreaker circuitBreaker(String name) {
        return CircuitBreaker.of(name, CircuitBreakerConfig.ofDefaults());
    }

    private Retry retry(String name) {
        return Retry.of(name, RetryConfig.custom().maxAttempts(1).build());
    }

    private RateLimiter rateLimiter(String name) {
        return RateLimiter.of(name, RateLimiterConfig.custom()
            .limitForPeriod(100)
            .limitRefreshPeriod(Duration.ofSeconds(1))
            .timeoutDuration(Duration.ZERO)
            .build());
    }
}
