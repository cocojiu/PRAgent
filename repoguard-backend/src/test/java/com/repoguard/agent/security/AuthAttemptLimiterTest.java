package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class AuthAttemptLimiterTest {

    @Test
    void enforcesInclusiveAccountIpLimitAndResetsAtNextMinute() {
        AuthProperties properties = propertiesWithLimits(2, 2);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MutableClock clock = new MutableClock();
        AuthAttemptLimiter limiter = new AuthAttemptLimiter(properties, registry, clock);
        String clientIp = "203.0.113.10";

        limiter.requireAllowed("LOGIN", " User@Example.com ", clientIp);
        limiter.requireAllowed("login", "user@example.com", clientIp);

        assertThatThrownBy(() -> limiter.requireAllowed("login", "USER@EXAMPLE.COM", clientIp))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS));
        assertThat(registry.counter("repoguard.auth.rate_limited", "operation", "login").count()).isEqualTo(1.0);

        clock.advance(Duration.ofMinutes(1));

        limiter.requireAllowed("login", "user@example.com", clientIp);
    }

    @Test
    void failsOpenForNewKeysAtCapacityWhileStillEnforcingTrackedKeys() {
        AuthProperties properties = propertiesWithLimits(2, 2);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MutableClock clock = new MutableClock();
        AuthAttemptLimiter limiter = new AuthAttemptLimiter(properties, registry, clock);

        for (int index = 0; index < 20_000; index++) {
            limiter.requireAllowed("login", "account-" + index, "client-" + index);
        }

        String overflowClient = "overflow-client";
        limiter.requireAllowed("login", "overflow-account", overflowClient);
        limiter.requireAllowed("login", "overflow-account", overflowClient);
        limiter.requireAllowed("login", "overflow-account", overflowClient);
        assertThat(registry.counter("repoguard.auth.rate_limited", "operation", "login").count()).isZero();
        assertThat(registry.counter("repoguard.auth.rate_limiter_saturated", "dimension", "ip").count()).isEqualTo(3.0);
        assertThat(registry.counter("repoguard.auth.rate_limiter_saturated", "dimension", "account-ip").count()).isEqualTo(3.0);

        limiter.requireAllowed("login", "account-0", "client-0");
        assertThatThrownBy(() -> limiter.requireAllowed("login", "account-0", "client-0"))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS));
        assertThat(registry.counter("repoguard.auth.rate_limited", "operation", "login").count()).isEqualTo(1.0);

        clock.advance(Duration.ofMinutes(1));

        limiter.requireAllowed("login", "overflow-account", overflowClient);
        limiter.requireAllowed("login", "overflow-account", overflowClient);
        assertThatThrownBy(() -> limiter.requireAllowed("login", "overflow-account", overflowClient))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS));
        assertThat(registry.counter("repoguard.auth.rate_limiter_saturated", "dimension", "ip").count()).isEqualTo(3.0);
        assertThat(registry.counter("repoguard.auth.rate_limiter_saturated", "dimension", "account-ip").count()).isEqualTo(3.0);
    }

    @Test
    void accountIpSaturationDoesNotConsumeIpDimensionCapacity() {
        AuthProperties properties = propertiesWithLimits(2, 1_000_000);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MutableClock clock = new MutableClock();
        AuthAttemptLimiter limiter = new AuthAttemptLimiter(properties, registry, clock);

        for (int index = 0; index < 10_500; index++) {
            String clientIp = "client-" + index;
            limiter.requireAllowed("login", "account-a-" + index, clientIp);
            limiter.requireAllowed("login", "account-b-" + index, clientIp);
        }

        String victimIp = "203.0.113.50";
        limiter.requireAllowed("login", "victim", victimIp);
        limiter.requireAllowed("login", "victim", victimIp);
        assertThatThrownBy(() -> limiter.requireAllowed("login", "victim", victimIp))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS));

        assertThat(registry.counter("repoguard.auth.rate_limited", "operation", "login").count()).isEqualTo(1.0);
        assertThat(registry.counter("repoguard.auth.rate_limiter_saturated", "dimension", "ip").count()).isZero();
        assertThat(registry.counter("repoguard.auth.rate_limiter_saturated", "dimension", "account-ip").count())
            .isGreaterThanOrEqualTo(2.0);
    }

    @Test
    void countsConcurrentAttemptsWithoutLosingOrOvercountingUpdates() throws Exception {
        int threads = 8;
        int attemptsPerThread = 250;
        int limit = threads * attemptsPerThread;
        AuthProperties properties = propertiesWithLimits(limit, limit);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AuthAttemptLimiter limiter = new AuthAttemptLimiter(properties, registry, new MutableClock());

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        try {
            for (int thread = 0; thread < threads; thread++) {
                futures.add(executor.submit((Callable<Void>) () -> {
                    start.await();
                    for (int attempt = 0; attempt < attemptsPerThread; attempt++) {
                        limiter.requireAllowed("login", "user@example.com", "203.0.113.10");
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(registry.counter("repoguard.auth.rate_limited", "operation", "login").count()).isZero();
        assertThatThrownBy(() -> limiter.requireAllowed("login", "user@example.com", "203.0.113.10"))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS));
    }

    private AuthProperties propertiesWithLimits(int perIp, int perAccountIp) {
        AuthProperties properties = new AuthProperties();
        properties.setPublicAuthRequestsPerMinutePerIp(perIp);
        properties.setPublicAuthRequestsPerMinutePerAccountIp(perAccountIp);
        return properties;
    }

    private static final class MutableClock extends Clock {
        private Instant current = Instant.parse("2026-07-13T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(current, zone);
        }

        @Override
        public Instant instant() {
            return current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }
    }
}
