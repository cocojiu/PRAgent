package com.repoguard.agent.security;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AuthAttemptLimiter {

    private static final int MAX_TRACKED_KEYS = 20_000;
    private final AuthProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public AuthAttemptLimiter(AuthProperties properties, MeterRegistry meterRegistry) {
        this(properties, meterRegistry, Clock.systemUTC());
    }

    AuthAttemptLimiter(AuthProperties properties, MeterRegistry meterRegistry, Clock clock) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    public void requireAllowed(String operation, String account, String clientIp) {
        String ip = normalize(clientIp);
        String normalizedOperation = normalize(operation);
        if (!acquire("ip:" + normalizedOperation + ":" + ip, properties.getPublicAuthRequestsPerMinutePerIp())
            || !acquire(
                "account-ip:" + normalizedOperation + ":" + Integer.toUnsignedString(normalize(account).hashCode()) + ":" + ip,
                properties.getPublicAuthRequestsPerMinutePerAccountIp()
            )) {
            meterRegistry.counter("repoguard.auth.rate_limited", "operation", normalizedOperation).increment();
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "Too many authentication attempts");
        }
    }

    private boolean acquire(String key, int limit) {
        long minute = clock.millis() / 60_000L;
        synchronized (windows) {
            Window current = windows.get(key);
            if (current == null && windows.size() >= MAX_TRACKED_KEYS) {
                windows.entrySet().removeIf(entry -> entry.getValue().minute() < minute);
                if (windows.size() >= MAX_TRACKED_KEYS) {
                    return false;
                }
            }
            Window result = current == null || current.minute() != minute
                ? new Window(minute, 1)
                : new Window(minute, current.count() + 1);
            windows.put(key, result);
            return result.count() <= limit;
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim().toLowerCase(Locale.ROOT);
    }

    private record Window(long minute, int count) {
    }
}
