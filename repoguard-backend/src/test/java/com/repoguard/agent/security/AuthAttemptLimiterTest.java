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
    void failsClosedForNewKeysAtCapacityButAllowsTrackedKeysAndPrunesExpiredWindows() {
        AuthProperties properties = propertiesWithLimits(2, 2);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MutableClock clock = new MutableClock();
        AuthAttemptLimiter limiter = new AuthAttemptLimiter(properties, registry, clock);

        for (int index = 0; index < 10_000; index++) {
            limiter.requireAllowed("login", "account-" + index, "client-" + index);
        }

        String overflowClient = "overflow-client";
        assertThatThrownBy(() -> limiter.requireAllowed("login", "overflow-account", overflowClient))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS));

        limiter.requireAllowed("login", "account-0", "client-0");

        clock.advance(Duration.ofMinutes(1));

        limiter.requireAllowed("login", "overflow-account", overflowClient);
        assertThat(registry.counter("repoguard.auth.rate_limited", "operation", "login").count()).isEqualTo(1.0);
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
