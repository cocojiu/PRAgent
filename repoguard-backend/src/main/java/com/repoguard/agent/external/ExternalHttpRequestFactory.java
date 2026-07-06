package com.repoguard.agent.external;

import java.time.Duration;
import java.util.Objects;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

public final class ExternalHttpRequestFactory {

    private static final int MIN_TIMEOUT_SECONDS = 1;

    private ExternalHttpRequestFactory() {
    }

    public static SimpleClientHttpRequestFactory simple(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(requirePositive(connectTimeout, "connectTimeout"));
        requestFactory.setReadTimeout(requirePositive(readTimeout, "readTimeout"));
        return requestFactory;
    }

    public static SimpleClientHttpRequestFactory sameTimeoutSeconds(Integer timeoutSeconds, int defaultTimeoutSeconds) {
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
