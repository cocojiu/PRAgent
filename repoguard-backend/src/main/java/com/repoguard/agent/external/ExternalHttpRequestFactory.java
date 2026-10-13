package com.repoguard.agent.external;

import java.time.Duration;
import java.net.http.HttpClient;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

public final class ExternalHttpRequestFactory {

    private static final int MIN_TIMEOUT_SECONDS = 1;
    private static final URI CONFIGURED_PROXY = parseProxy(System.getenv("REPOGUARD_OUTBOUND_HTTP_PROXY"));
    private static final Map<ClientKey, HttpClient> SHARED_CLIENTS = new ConcurrentHashMap<>();

    private ExternalHttpRequestFactory() {
    }

    public static ClientHttpRequestFactory simple(Duration connectTimeout, Duration readTimeout) {
        return simple(connectTimeout, readTimeout, CONFIGURED_PROXY);
    }

    static ClientHttpRequestFactory simple(Duration connectTimeout, Duration readTimeout, URI proxy) {
        Duration effectiveConnectTimeout = requirePositive(connectTimeout, "connectTimeout");
        Duration effectiveReadTimeout = requirePositive(readTimeout, "readTimeout");
        HttpClient httpClient = SHARED_CLIENTS.computeIfAbsent(
            new ClientKey(effectiveConnectTimeout, proxy),
            key -> {
                HttpClient.Builder builder = HttpClient.newBuilder()
                    .connectTimeout(key.connectTimeout())
                    .followRedirects(HttpClient.Redirect.NEVER);
                if (key.proxy() != null) {
                    builder.proxy(ProxySelector.of(InetSocketAddress.createUnresolved(
                        key.proxy().getHost(), key.proxy().getPort())));
                }
                return builder.build();
            }
        );
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(effectiveReadTimeout);
        return requestFactory;
    }

    /** Ensure all injected clients, including optional SCM adapters, use the explicit gateway. */
    public static RestClient.Builder applyConfiguredProxy(RestClient.Builder builder) {
        return CONFIGURED_PROXY == null ? builder : builder.requestFactory(simple(Duration.ofSeconds(5), Duration.ofSeconds(30)));
    }

    public static void validateConfiguredProxy() {
        // Class initialization validates the immutable process setting before outbound clients start.
    }

    static URI parseProxy(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value.trim());
            if (value.length() > 512 || !"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getPort() < 1 || uri.getPort() > 65535 || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))) {
                throw new IllegalArgumentException();
            }
            return URI.create("http://" + (uri.getHost().contains(":") ? "[" + uri.getHost().replace("[", "").replace("]", "") + "]" : uri.getHost()) + ":" + uri.getPort());
        } catch (IllegalArgumentException rejected) {
            // Never echo proxy credentials or untrusted configuration in startup errors.
            throw new IllegalArgumentException("REPOGUARD_OUTBOUND_HTTP_PROXY must be an HTTP proxy host and explicit port without credentials, path, query or fragment");
        }
    }

    private record ClientKey(Duration connectTimeout, URI proxy) {
    }

    public static ClientHttpRequestFactory sameTimeoutSeconds(Integer timeoutSeconds, int defaultTimeoutSeconds) {
        Duration timeout = Duration.ofSeconds(normalizedTimeoutSeconds(timeoutSeconds, defaultTimeoutSeconds));
        return simple(timeout, timeout);
    }

    public static ClientHttpRequestFactory cappedConnectTimeoutSeconds(
        Integer readTimeoutSeconds,
        int defaultReadTimeoutSeconds,
        int maxConnectTimeoutSeconds
    ) {
        int readSeconds = normalizedTimeoutSeconds(readTimeoutSeconds, defaultReadTimeoutSeconds);
        int connectSeconds = Math.min(readSeconds, Math.max(MIN_TIMEOUT_SECONDS, maxConnectTimeoutSeconds));
        return simple(Duration.ofSeconds(connectSeconds), Duration.ofSeconds(readSeconds));
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
