package com.repoguard.agent.external;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import java.util.function.Function;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ExternalCallResilienceConfig {

    @Bean
    public ExternalCallResilience externalCallResilience(ExternalCallResilienceProperties properties) {
        return new ExternalCallResilience(
            circuitBreaker("github", properties.getGithub()),
            retry("github", properties.getGithub(), ExternalCallErrorClassifier::github),
            rateLimiter("github", properties.getGithub()),
            circuitBreaker("llm", properties.getLlm()),
            retry("llm", properties.getLlm(), ExternalCallErrorClassifier::llm),
            rateLimiter("llm", properties.getLlm()),
            bulkhead("llm", properties.getLlm())
        );
    }

    private CircuitBreaker circuitBreaker(String name, ExternalCallResilienceProperties.Instance properties) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
            .failureRateThreshold(Math.max(1, properties.getCircuitBreakerFailureRateThreshold()))
            .slidingWindowSize(Math.max(2, properties.getCircuitBreakerSlidingWindowSize()))
            .minimumNumberOfCalls(Math.max(1, properties.getCircuitBreakerMinimumCalls()))
            .waitDurationInOpenState(Duration.ofSeconds(Math.max(1, properties.getCircuitBreakerWaitOpenSeconds())))
            .recordException(throwable -> true)
            .build();
        return CircuitBreaker.of("repoguard-" + name, config);
    }

    private Retry retry(
        String name,
        ExternalCallResilienceProperties.Instance properties,
        Function<RuntimeException, ExternalCallException> classifier
    ) {
        RetryConfig config = RetryConfig.custom()
            .maxAttempts(Math.max(1, properties.getRetryMaxAttempts()))
            .waitDuration(Duration.ofMillis(Math.max(0, properties.getRetryWaitMillis())))
            .retryOnException(throwable -> isRetryable(classifier, throwable))
            .build();
        return Retry.of("repoguard-" + name, config);
    }

    private boolean isRetryable(
        Function<RuntimeException, ExternalCallException> classifier,
        Throwable throwable
    ) {
        if (!(throwable instanceof RuntimeException runtimeException)) {
            return false;
        }
        return classifier.apply(runtimeException).isRetryable();
    }

    private RateLimiter rateLimiter(String name, ExternalCallResilienceProperties.Instance properties) {
        RateLimiterConfig config = RateLimiterConfig.custom()
            .limitForPeriod(Math.max(1, properties.getRateLimitForPeriod()))
            .limitRefreshPeriod(Duration.ofMillis(Math.max(1, properties.getRateLimitRefreshMillis())))
            .timeoutDuration(Duration.ofMillis(Math.max(0, properties.getRateLimitTimeoutMillis())))
            .build();
        return RateLimiter.of("repoguard-" + name, config);
    }

    private Bulkhead bulkhead(String name, ExternalCallResilienceProperties.Instance properties) {
        BulkheadConfig config = BulkheadConfig.custom()
            .maxConcurrentCalls(Math.max(1, properties.getBulkheadMaxConcurrentCalls()))
            .maxWaitDuration(Duration.ofMillis(Math.max(0, properties.getBulkheadMaxWaitMillis())))
            .build();
        return Bulkhead.of("repoguard-" + name, config);
    }
}
