package com.repoguard.agent.github.webhook;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.security.DatabaseRateLimitWindowStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class GithubWebhookRateLimiter {

    private static final Logger LOGGER = LoggerFactory.getLogger(GithubWebhookRateLimiter.class);
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final GithubWebhookProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final DatabaseRateLimitWindowStore sharedWindowStore;
    private final Dimension ipDimension = new Dimension("ip");
    private final Dimension repositoryDimension = new Dimension("repository");

    @Autowired
    public GithubWebhookRateLimiter(
        GithubWebhookProperties properties,
        MeterRegistry meterRegistry,
        ObjectProvider<DatabaseRateLimitWindowStore> sharedWindowStoreProvider
    ) {
        this(properties, meterRegistry, Clock.systemUTC(), sharedWindowStoreProvider.getIfAvailable());
    }

    GithubWebhookRateLimiter(GithubWebhookProperties properties, MeterRegistry meterRegistry, Clock clock) {
        this(properties, meterRegistry, clock, null);
    }

    GithubWebhookRateLimiter(
        GithubWebhookProperties properties,
        MeterRegistry meterRegistry,
        Clock clock,
        DatabaseRateLimitWindowStore sharedWindowStore
    ) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        this.sharedWindowStore = sharedWindowStore;
    }

    public boolean tryAcquireIp(String clientIp) {
        return tryAcquire(ipDimension, normalize(clientIp), properties.getMaxRequestsPerMinutePerIp());
    }

    public void requireRepository(String repository) {
        if (!tryAcquire(
            repositoryDimension,
            normalize(repository),
            properties.getMaxRequestsPerMinutePerRepository()
        )) {
            rejected("repository_rate_limit");
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "GitHub webhook rate limit exceeded");
        }
    }

    public void rejected(String reason) {
        meterRegistry.counter("repoguard.github.webhook.rejected", "reason", normalize(reason)).increment();
    }

    private boolean tryAcquire(Dimension dimension, String key, int limit) {
        long minute = clock.millis() / 60_000L;
        pruneExpiredAtCapacity(dimension, minute);
        AtomicBoolean saturated = new AtomicBoolean();
        Window result = dimension.windows.compute(key, (ignored, current) -> {
            if (current == null && !dimension.reserveSlot()) {
                saturated.set(true);
                return null;
            }
            return current == null || current.minute() != minute
                ? new Window(minute, 1)
                : new Window(minute, current.count() + 1);
        });
        if (saturated.get()) {
            meterRegistry.counter(
                "repoguard.github.webhook.rate_limiter_saturated",
                "dimension",
                dimension.name
            ).increment();
            dimension.logSaturationOncePerMinute(minute);
            return false;
        }
        if (sharedWindowStore != null) {
            return sharedWindowStore.tryAcquire("github-webhook-" + dimension.name, key, minute, limit);
        }
        return result.count() <= limit;
    }

    private void pruneExpiredAtCapacity(Dimension dimension, long minute) {
        if (dimension.trackedKeys.get() < MAX_TRACKED_KEYS) {
            return;
        }
        long lastPrunedMinute = dimension.pruneMinute.get();
        if (lastPrunedMinute == minute || !dimension.pruneMinute.compareAndSet(lastPrunedMinute, minute)) {
            return;
        }
        dimension.windows.forEach((key, window) -> {
            if (window.minute() < minute && dimension.windows.remove(key, window)) {
                dimension.trackedKeys.decrementAndGet();
            }
        });
    }

    private String normalize(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "unknown";
    }

    private static final class Dimension {

        private final String name;
        private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
        private final AtomicInteger trackedKeys = new AtomicInteger();
        private final AtomicLong pruneMinute = new AtomicLong(Long.MIN_VALUE);
        private final AtomicLong saturationLogMinute = new AtomicLong(Long.MIN_VALUE);

        private Dimension(String name) {
            this.name = name;
        }

        private boolean reserveSlot() {
            while (true) {
                int current = trackedKeys.get();
                if (current >= MAX_TRACKED_KEYS) {
                    return false;
                }
                if (trackedKeys.compareAndSet(current, current + 1)) {
                    return true;
                }
            }
        }

        private void logSaturationOncePerMinute(long minute) {
            long lastLoggedMinute = saturationLogMinute.get();
            if (lastLoggedMinute != minute && saturationLogMinute.compareAndSet(lastLoggedMinute, minute)) {
                LOGGER.warn(
                    "GitHub webhook rate limiter {} dimension saturated at {} tracked keys; rejecting new keys",
                    name,
                    MAX_TRACKED_KEYS
                );
            }
        }
    }

    private record Window(long minute, int count) {
    }
}
