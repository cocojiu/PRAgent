package com.repoguard.agent.github.webhook;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class GithubWebhookRateLimiterTest {

    @Test
    void enforcesInclusiveIpLimitAndResetsAtNextMinute() {
        GithubWebhookProperties properties = new GithubWebhookProperties();
        properties.setMaxRequestsPerMinutePerIp(2);
        MutableClock clock = new MutableClock();
        GithubWebhookRateLimiter limiter = new GithubWebhookRateLimiter(
            properties,
            new SimpleMeterRegistry(),
            clock
        );

        assertThat(limiter.tryAcquireIp(" Client-IP ")).isTrue();
        assertThat(limiter.tryAcquireIp("client-ip")).isTrue();
        assertThat(limiter.tryAcquireIp("CLIENT-IP")).isFalse();

        clock.advance(Duration.ofMinutes(1));

        assertThat(limiter.tryAcquireIp("client-ip")).isTrue();
    }

    @Test
    void repositoryLimitThrowsTypedErrorAndRecordsReason() {
        GithubWebhookProperties properties = new GithubWebhookProperties();
        properties.setMaxRequestsPerMinutePerRepository(1);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GithubWebhookRateLimiter limiter = new GithubWebhookRateLimiter(properties, registry, new MutableClock());

        limiter.requireRepository("Org/Repo");

        assertThatThrownBy(() -> limiter.requireRepository(" org/repo "))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS));
        assertThat(registry.counter(
            "repoguard.github.webhook.rejected",
            "reason",
            "repository_rate_limit"
        ).count()).isEqualTo(1.0);
    }

    @Test
    void failsClosedForNewKeysAtCapacityButKeepsExistingWindowsUsable() {
        GithubWebhookProperties properties = new GithubWebhookProperties();
        properties.setMaxRequestsPerMinutePerIp(2);
        MutableClock clock = new MutableClock();
        GithubWebhookRateLimiter limiter = new GithubWebhookRateLimiter(
            properties,
            new SimpleMeterRegistry(),
            clock
        );

        for (int index = 0; index < 10_000; index++) {
            assertThat(limiter.tryAcquireIp("client-" + index)).isTrue();
        }

        assertThat(limiter.tryAcquireIp("overflow-client")).isFalse();
        assertThat(limiter.tryAcquireIp("client-0")).isTrue();

        clock.advance(Duration.ofMinutes(1));

        assertThat(limiter.tryAcquireIp("overflow-client")).isTrue();
    }

    @Test
    void sameKeyIsCountedAtomicallyWithoutSerializingUnrelatedKeys() throws Exception {
        GithubWebhookProperties properties = new GithubWebhookProperties();
        properties.setMaxRequestsPerMinutePerIp(25);
        GithubWebhookRateLimiter limiter = new GithubWebhookRateLimiter(
            properties,
            new SimpleMeterRegistry(),
            new MutableClock()
        );
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(12)) {
            for (int index = 0; index < 100; index++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return limiter.tryAcquireIp("same-client");
                }));
            }
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> result : results) {
                if (result.get()) {
                    accepted++;
                }
            }
            assertThat(accepted).isEqualTo(25);
        }
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
