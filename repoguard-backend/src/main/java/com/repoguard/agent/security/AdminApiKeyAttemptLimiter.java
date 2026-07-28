package com.repoguard.agent.security;

import com.repoguard.agent.common.TrustedProxyClientIpResolver;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AdminApiKeyAttemptLimiter {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminApiKeyAttemptLimiter.class);

    private final AdminApiKeyProperties properties;
    private final MeterRegistry meterRegistry;
    private final TrustedProxyClientIpResolver clientIpResolver;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicInteger trackedClients = new AtomicInteger();
    private final AtomicLong pruneMinute = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong saturationLogMinute = new AtomicLong(Long.MIN_VALUE);

    @Autowired
    public AdminApiKeyAttemptLimiter(
        AdminApiKeyProperties properties,
        MeterRegistry meterRegistry,
        TrustedProxyClientIpResolver clientIpResolver
    ) {
        this(properties, meterRegistry, clientIpResolver, Clock.systemUTC());
    }

    AdminApiKeyAttemptLimiter(
        AdminApiKeyProperties properties,
        MeterRegistry meterRegistry,
        TrustedProxyClientIpResolver clientIpResolver,
        Clock clock
    ) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clientIpResolver = clientIpResolver;
        this.clock = clock;
    }

    public boolean recordFailureAllowed(HttpServletRequest request) {
        String resolvedClientIp = clientIpResolver.resolve(request);
        String clientIp = resolvedClientIp == null || resolvedClientIp.isBlank() ? "unknown" : resolvedClientIp;
        long minute = clock.millis() / 60_000L;
        pruneExpiredAtCapacity(minute);

        AtomicBoolean saturated = new AtomicBoolean();
        Window result = windows.compute(clientIp, (ignored, current) -> {
            if (current == null && !reserveClientSlot()) {
                saturated.set(true);
                return null;
            }
            return current == null || current.minute() != minute
                ? new Window(minute, 1)
                : new Window(minute, current.count() + 1);
        });
        if (saturated.get()) {
            meterRegistry.counter("repoguard.security.admin_api_key.rate_limiter_saturated").increment();
            logSaturationOncePerMinute(minute);
            return false;
        }
        boolean allowed = result.count() <= properties.getFailedRequestsPerMinutePerIp();
        if (!allowed) {
            meterRegistry.counter("repoguard.security.admin_api_key.rate_limited").increment();
        }
        return allowed;
    }

    private boolean reserveClientSlot() {
        while (true) {
            int current = trackedClients.get();
            if (current >= properties.getMaxTrackedClients()) {
                return false;
            }
            if (trackedClients.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void pruneExpiredAtCapacity(long minute) {
        if (trackedClients.get() < properties.getMaxTrackedClients()) {
            return;
        }
        long lastPrunedMinute = pruneMinute.get();
        if (lastPrunedMinute == minute || !pruneMinute.compareAndSet(lastPrunedMinute, minute)) {
            return;
        }
        windows.forEach((key, window) -> {
            if (window.minute() < minute && windows.remove(key, window)) {
                trackedClients.decrementAndGet();
            }
        });
    }

    private void logSaturationOncePerMinute(long minute) {
        long lastLoggedMinute = saturationLogMinute.get();
        if (lastLoggedMinute != minute && saturationLogMinute.compareAndSet(lastLoggedMinute, minute)) {
            LOGGER.warn(
                "Admin API key failure limiter saturated at {} tracked clients; rejecting untracked clients",
                properties.getMaxTrackedClients()
            );
        }
    }

    private record Window(long minute, int count) {
    }
}
