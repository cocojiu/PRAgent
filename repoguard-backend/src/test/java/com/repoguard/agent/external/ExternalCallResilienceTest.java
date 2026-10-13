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
import io.github.resilience4j.retry.event.RetryOnRetryEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientResponseException;

class ExternalCallResilienceTest {

    private final ExternalCallRetryMetricsRecorder retryMetricsRecorder = org.mockito.Mockito.mock(
        ExternalCallRetryMetricsRecorder.class
    );

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

    @Test
    void configuredGithubRetrySkipsNonRetryableHttpFailures() {
        ExternalCallResilience resilience = configuredResilience(3);
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> resilience.github("list_open_pull_requests", () -> {
            attempts.incrementAndGet();
            throw responseException(401, "Bad credentials");
        }))
            .isInstanceOf(RestClientResponseException.class);

        assertThat(attempts).hasValue(1);
        org.mockito.Mockito.verify(retryMetricsRecorder, org.mockito.Mockito.never())
            .record(org.mockito.Mockito.any(), org.mockito.Mockito.any());
    }

    @Test
    void configuredGithubRetryRetriesRetryableHttpFailures() {
        ExternalCallResilience resilience = configuredResilience(3);
        AtomicInteger attempts = new AtomicInteger();

        String result = resilience.github("fetch_pull_request_diff", () -> {
            if (attempts.incrementAndGet() < 3) {
                throw responseException(502, "Bad gateway");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
        org.mockito.Mockito.verify(retryMetricsRecorder, org.mockito.Mockito.times(2))
            .record(org.mockito.Mockito.any(), org.mockito.Mockito.any(RetryOnRetryEvent.class));
    }

    @Test
    void configuredLlmRetrySkipsInvalidRequestFailures() {
        ExternalCallResilience resilience = configuredResilience(3);
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> resilience.llm("chat_completions", () -> {
            attempts.incrementAndGet();
            throw responseException(422, "Invalid request");
        }))
            .isInstanceOf(RestClientResponseException.class);

        assertThat(attempts).hasValue(1);
        org.mockito.Mockito.verify(retryMetricsRecorder, org.mockito.Mockito.never())
            .record(org.mockito.Mockito.any(), org.mockito.Mockito.any());
    }

    private ExternalCallResilience resilience(CircuitBreaker githubCircuitBreaker, CircuitBreaker llmCircuitBreaker) {
        return resilience(
            githubCircuitBreaker,
            llmCircuitBreaker,
            Bulkhead.of("llm-test", BulkheadConfig.custom().maxConcurrentCalls(1).build())
        );
    }

    @Test
    void eachRetryAcquiresAPermitAndLocalRejectionDoesNotRetryAgain() {
        var properties = new ExternalCallResilienceProperties();
        properties.getLlm().setRetryMaxAttempts(3);
        properties.getLlm().setRetryWaitMillis(0);
        properties.getLlm().setRateLimitForPeriod(1);
        properties.getLlm().setRateLimitRefreshMillis(60000);
        properties.getLlm().setRateLimitTimeoutMillis(0);
        var resilience = new ExternalCallResilienceConfig().externalCallResilience(properties, retryMetricsRecorder);
        var attempts = new AtomicInteger();
        assertThatThrownBy(() -> resilience.llm("review", () -> {
            attempts.incrementAndGet(); throw responseException(503, "fixture unavailable");
        })).isInstanceOfSatisfying(ExternalCallException.class,
            denied -> assertThat(denied.getCategory()).isEqualTo("llm_rate_limited"));
        assertThat(attempts).hasValue(1);
        org.mockito.Mockito.verify(retryMetricsRecorder, org.mockito.Mockito.times(1)).record(
            org.mockito.Mockito.any(), org.mockito.Mockito.any());
    }

    @Test
    void providerFaultDomainsShareGlobalLimitsButDoNotShareOpenBreakers() {
        var properties = new ExternalCallResilienceProperties();
        properties.setLlmMaxEndpointDomains(2);
        properties.getLlm().setRetryMaxAttempts(1);
        properties.getLlm().setCircuitBreakerMinimumCalls(1);
        var resilience = new ExternalCallResilienceConfig().externalCallResilience(properties, retryMetricsRecorder);
        assertThatThrownBy(() -> resilience.llm("review", "a", "https://a.invalid/v1", () -> Long.MAX_VALUE,
            () -> { throw responseException(503, "fixture failure"); })).isInstanceOf(RestClientResponseException.class);
        var attempted = new AtomicInteger();
        assertThatThrownBy(() -> resilience.llm("review", "a", "https://a.invalid/another-path", () -> Long.MAX_VALUE,
            () -> attempted.incrementAndGet())).hasMessageContaining("llm_circuit_open");
        assertThat(attempted).hasValue(0);
        assertThat(resilience.llm("review", "b", "https://b.invalid/v1", () -> Long.MAX_VALUE, () -> "ok")).isEqualTo("ok");
        assertThatThrownBy(() -> resilience.llm("review", "c", "https://c.invalid/v1", () -> Long.MAX_VALUE,
            () -> attempted.incrementAndGet())).hasMessageContaining("endpoint_registry_full");
        assertThat(resilience.retainedEndpointDomains()).isEqualTo(2);
        assertThat(attempted).hasValue(0);
    }

    @Test
    void expiredBudgetStopsInitialAttemptAndRetryBeforeProviderWork() {
        var resilience = configuredResilience(3);
        var remaining = new java.util.concurrent.atomic.AtomicLong(0);
        var attempted = new AtomicInteger();
        assertThatThrownBy(() -> resilience.llm("review", "a", "https://a.invalid/v1", remaining::get,
            () -> attempted.incrementAndGet())).hasMessageContaining("deadline_exhausted");
        assertThat(attempted).hasValue(0);
        remaining.set(Long.MAX_VALUE);
        assertThatThrownBy(() -> resilience.llm("review", "a", "https://a.invalid/v1", remaining::get, () -> {
            attempted.incrementAndGet(); remaining.set(0); throw responseException(503, "fixture unavailable");
        })).hasMessageContaining("deadline_exhausted");
        assertThat(attempted).hasValue(1);
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

    private ExternalCallResilience configuredResilience(int retryMaxAttempts) {
        ExternalCallResilienceProperties properties = new ExternalCallResilienceProperties();
        properties.getGithub().setRetryMaxAttempts(retryMaxAttempts);
        properties.getGithub().setRetryWaitMillis(0);
        properties.getLlm().setRetryMaxAttempts(retryMaxAttempts);
        properties.getLlm().setRetryWaitMillis(0);
        return new ExternalCallResilienceConfig().externalCallResilience(properties, retryMetricsRecorder);
    }

    private RestClientResponseException responseException(int statusCode, String statusText) {
        return new RestClientResponseException(
            statusText,
            statusCode,
            statusText,
            null,
            new byte[0],
            StandardCharsets.UTF_8
        );
    }
}
