package com.repoguard.agent.external;

import java.time.Duration;
import java.net.http.HttpClient;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;

public final class ExternalHttpRequestFactory {

    private static final int MIN_TIMEOUT_SECONDS = 1;
    private static final Map<Duration, HttpClient> SHARED_CLIENTS = new ConcurrentHashMap<>();

    private ExternalHttpRequestFactory() {
    }

    public static ClientHttpRequestFactory simple(Duration connectTimeout, Duration readTimeout) {
        Duration effectiveConnectTimeout = requirePositive(connectTimeout, "connectTimeout");
        Duration effectiveReadTimeout = requirePositive(readTimeout, "readTimeout");
        HttpClient httpClient = SHARED_CLIENTS.computeIfAbsent(
            effectiveConnectTimeout,
            timeout -> HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
        );
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(effectiveReadTimeout);
        return requestFactory;
    }

    public static ClientHttpRequestFactory sameTimeoutSeconds(Integer timeoutSeconds, int defaultTimeoutSeconds) {
        Duration timeout = Duration.ofSeconds(normalizedTimeoutSeconds(timeoutSeconds, defaultTimeoutSeconds));
        return simple(timeout, timeout);
    }

    static int normalizedTimeoutSeconds(Integer timeoutSeconds, int defaultTimeoutSeconds) {
        return Math.max(MIN_TIMEOUT_SECONDS, timeoutSeconds == null ? defaultTimeoutSeconds : timeoutSeconds);
    }

    private static Duration requirePositive(Duration timeout, String name) {
        Duration value = Objects.requireNonNull(timeout, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
