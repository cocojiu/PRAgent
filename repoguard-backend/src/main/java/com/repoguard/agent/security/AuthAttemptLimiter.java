package com.repoguard.agent.security;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AuthAttemptLimiter {

    private static final Logger LOGGER = LoggerFactory.getLogger(AuthAttemptLimiter.class);
    private static final int MAX_TRACKED_KEYS_PER_DIMENSION = 20_000;
    private final AuthProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final Dimension ipDimension = new Dimension("ip");
    private final Dimension accountIpDimension = new Dimension("account-ip");

    @Autowired
    public AuthAttemptLimiter(AuthProperties properties, MeterRegistry meterRegistry) {
        this(properties, meterRegistry, Clock.systemUTC());
    }

    AuthAttemptLimiter(AuthProperties properties, MeterRegistry meterRegistry, Clock clock) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        LOGGER.info(
            "Auth attempt limits active: {}/min per ip, {}/min per account-ip; thresholds are per-instance, adjust them when running more than one API instance",
            properties.getPublicAuthRequestsPerMinutePerIp(),
            properties.getPublicAuthRequestsPerMinutePerAccountIp()
        );
    }

    public void requireAllowed(String operation, String account, String clientIp) {
        String ip = normalize(clientIp);
        String normalizedOperation = normalize(operation);
        if (!acquire(ipDimension, normalizedOperation + ":" + ip, properties.getPublicAuthRequestsPerMinutePerIp())
            || !acquire(
                accountIpDimension,
                normalizedOperation + ":" + Integer.toUnsignedString(normalize(account).hashCode()) + ":" + ip,
                properties.getPublicAuthRequestsPerMinutePerAccountIp()
            )) {
            meterRegistry.counter("repoguard.auth.rate_limited", "operation", normalizedOperation).increment();
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "Too many authentication attempts");
        }
    }

    private boolean acquire(Dimension dimension, String key, int limit) {
        long minute = clock.millis() / 60_000L;
        if (!dimension.windows.containsKey(key) && dimension.windows.size() >= MAX_TRACKED_KEYS_PER_DIMENSION) {
            long lastPrunedMinute = dimension.pruneMinute.get();
            if (lastPrunedMinute != minute && dimension.pruneMinute.compareAndSet(lastPrunedMinute, minute)) {
                dimension.windows.entrySet().removeIf(entry -> entry.getValue().minute() < minute);
            }
            if (dimension.windows.size() >= MAX_TRACKED_KEYS_PER_DIMENSION) {
                meterRegistry.counter("repoguard.auth.rate_limiter_saturated", "dimension", dimension.name).increment();
                long lastLoggedMinute = dimension.saturationLogMinute.get();
                if (lastLoggedMinute != minute && dimension.saturationLogMinute.compareAndSet(lastLoggedMinute, minute)) {
                    LOGGER.warn(
                        "Auth attempt limiter {} dimension saturated at {} tracked keys; allowing new keys without limiting",
                        dimension.name,
                        MAX_TRACKED_KEYS_PER_DIMENSION
                    );
                }
                return true;
            }
        }
        Window window = dimension.windows.compute(key, (ignored, current) ->
            current == null || current.minute() != minute
                ? new Window(minute, 1)
                : new Window(minute, current.count() + 1));
        return window.count() <= limit;
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static final class Dimension {

        private final String name;
        private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
        private final AtomicLong pruneMinute = new AtomicLong(Long.MIN_VALUE);
        private final AtomicLong saturationLogMinute = new AtomicLong(Long.MIN_VALUE);

        private Dimension(String name) {
            this.name = name;
        }
    }

    private record Window(long minute, int count) {
    }
}
