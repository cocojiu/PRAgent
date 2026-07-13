package com.repoguard.agent.github.webhook;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class GithubWebhookRateLimiter {

    private static final int MAX_TRACKED_KEYS = 10_000;

    private final GithubWebhookProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> ipWindows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Window> repositoryWindows = new ConcurrentHashMap<>();

    public GithubWebhookRateLimiter(GithubWebhookProperties properties, MeterRegistry meterRegistry) {
        this(properties, meterRegistry, Clock.systemUTC());
    }

    GithubWebhookRateLimiter(GithubWebhookProperties properties, MeterRegistry meterRegistry, Clock clock) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    public boolean tryAcquireIp(String clientIp) {
        return tryAcquire(ipWindows, normalize(clientIp), properties.getMaxRequestsPerMinutePerIp());
    }

    public void requireRepository(String repository) {
        if (!tryAcquire(repositoryWindows, normalize(repository), properties.getMaxRequestsPerMinutePerRepository())) {
            rejected("repository_rate_limit");
            throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS, "GitHub webhook rate limit exceeded");
        }
    }

    public void rejected(String reason) {
        meterRegistry.counter("repoguard.github.webhook.rejected", "reason", normalize(reason)).increment();
    }

    private boolean tryAcquire(ConcurrentHashMap<String, Window> windows, String key, int limit) {
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
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "unknown";
    }

    private record Window(long minute, int count) {
    }
}
