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
import org.springframework.mock.web.MockHttpServletRequest;

class AuthAttemptLimiterTest {

    @Test
    void enforcesInclusiveAccountIpLimitAndResetsAtNextMinute() {
        AuthProperties properties = propertiesWithLimits(2, 2);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MutableClock clock = new MutableClock();
        AuthAttemptLimiter limiter = new AuthAttemptLimiter(properties, registry, clock);
        MockHttpServletRequest request = requestFrom("203.0.113.10");

        limiter.requireAllowed("LOGIN", " User@Example.com ", request);
        limiter.requireAllowed("login", "user@example.com", request);

        assertThatThrownBy(() -> limiter.requireAllowed("login", "USER@EXAMPLE.COM", request))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS));
        assertThat(registry.counter("repoguard.auth.rate_limited", "operation", "login").count()).isEqualTo(1.0);

        clock.advance(Duration.ofMinutes(1));

        limiter.requireAllowed("login", "user@example.com", request);
    }

    @Test
    void failsClosedForNewKeysAtCapacityButAllowsTrackedKeysAndPrunesExpiredWindows() {
        AuthProperties properties = propertiesWithLimits(2, 2);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MutableClock clock = new MutableClock();
        AuthAttemptLimiter limiter = new AuthAttemptLimiter(properties, registry, clock);

        for (int index = 0; index < 10_000; index++) {
            limiter.requireAllowed("login", "account-" + index, requestFrom("client-" + index));
        }

        MockHttpServletRequest overflowRequest = requestFrom("overflow-client");
        assertThatThrownBy(() -> limiter.requireAllowed("login", "overflow-account", overflowRequest))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS));

        limiter.requireAllowed("login", "account-0", requestFrom("client-0"));

        clock.advance(Duration.ofMinutes(1));

        limiter.requireAllowed("login", "overflow-account", overflowRequest);
        assertThat(registry.counter("repoguard.auth.rate_limited", "operation", "login").count()).isEqualTo(1.0);
    }

    private AuthProperties propertiesWithLimits(int perIp, int perAccountIp) {
        AuthProperties properties = new AuthProperties();
        properties.setPublicAuthRequestsPerMinutePerIp(perIp);
        properties.setPublicAuthRequestsPerMinutePerAccountIp(perAccountIp);
        return properties;
    }

    private MockHttpServletRequest requestFrom(String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr(ip);
        return request;
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
