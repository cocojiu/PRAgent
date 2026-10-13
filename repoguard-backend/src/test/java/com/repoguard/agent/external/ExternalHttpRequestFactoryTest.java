package com.repoguard.agent.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;

class ExternalHttpRequestFactoryTest {

    @Test
    void simpleAppliesConnectAndReadTimeoutsWithoutFollowingRedirects() throws ReflectiveOperationException {
        Object requestFactory = ExternalHttpRequestFactory.simple(Duration.ofSeconds(5), Duration.ofSeconds(8));

        assertThat(requestFactory).isInstanceOf(JdkClientHttpRequestFactory.class);
        assertThat(durationField(requestFactory, "readTimeout")).isEqualTo(Duration.ofSeconds(8));
        HttpClient httpClient = (HttpClient) field(requestFactory, "httpClient");
        assertThat(httpClient.connectTimeout()).contains(Duration.ofSeconds(5));
        assertThat(httpClient.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
    }

    @Test
    void sameTimeoutSecondsUsesDefaultAndMinimumTimeouts() throws ReflectiveOperationException {
        Object defaultFactory = ExternalHttpRequestFactory.sameTimeoutSeconds(null, 60);
        Object minimumFactory = ExternalHttpRequestFactory.sameTimeoutSeconds(0, 60);

        assertThat(durationField(defaultFactory, "readTimeout")).isEqualTo(Duration.ofSeconds(60));
        assertThat(client(defaultFactory).connectTimeout()).contains(Duration.ofSeconds(60));
        assertThat(durationField(minimumFactory, "readTimeout")).isEqualTo(Duration.ofSeconds(1));
        assertThat(client(minimumFactory).connectTimeout()).contains(Duration.ofSeconds(1));
    }

    @Test
    void factoriesWithSameConnectBudgetReuseOnePooledJdkClient() throws ReflectiveOperationException {
        Object first = ExternalHttpRequestFactory.simple(Duration.ofSeconds(5), Duration.ofSeconds(8));
        Object second = ExternalHttpRequestFactory.simple(Duration.ofSeconds(5), Duration.ofSeconds(20));

        assertThat(client(first)).isSameAs(client(second));
    }

    @Test
    void capsConnectTimeoutWithoutReducingReadTimeout() throws ReflectiveOperationException {
        Object factory = ExternalHttpRequestFactory.cappedConnectTimeoutSeconds(90, 60, 10);
        Object shortFactory = ExternalHttpRequestFactory.cappedConnectTimeoutSeconds(5, 60, 10);

        assertThat(durationField(factory, "readTimeout")).isEqualTo(Duration.ofSeconds(90));
        assertThat(client(factory).connectTimeout()).contains(Duration.ofSeconds(10));
        assertThat(durationField(shortFactory, "readTimeout")).isEqualTo(Duration.ofSeconds(5));
        assertThat(client(shortFactory).connectTimeout()).contains(Duration.ofSeconds(5));
    }

    @Test
    void explicitProxyReceivesTheRequestAndRedirectsNeverReachTheOrigin() throws Exception {
        AtomicInteger originRequests = new AtomicInteger();
        AtomicInteger gatewayRequests = new AtomicInteger();
        HttpServer origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer gateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/", exchange -> { originRequests.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close(); });
        String originUrl = "http://127.0.0.1:" + origin.getAddress().getPort() + "/fixture";
        gateway.createContext("/", exchange -> {
            gatewayRequests.incrementAndGet();
            exchange.getResponseHeaders().set("Location", originUrl);
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        origin.start(); gateway.start();
        try {
            var factory = ExternalHttpRequestFactory.simple(Duration.ofSeconds(1), Duration.ofSeconds(2),
                ExternalHttpRequestFactory.parseProxy("http://127.0.0.1:" + gateway.getAddress().getPort()));
            var response = RestClient.builder().requestFactory(factory).build().get().uri(originUrl).retrieve().toBodilessEntity();
            assertThat(response.getStatusCode().value()).isEqualTo(302);
            assertThat(gatewayRequests).hasValue(1);
            assertThat(originRequests).hasValue(0);
        } finally { gateway.stop(0); origin.stop(0); }
    }

    @Test
    void unavailableExplicitProxyDoesNotFallBackToAnAvailableOrigin() throws Exception {
        AtomicInteger originRequests = new AtomicInteger();
        HttpServer origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/", exchange -> { originRequests.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close(); });
        origin.start();
        int unavailablePort;
        try (ServerSocket unused = new ServerSocket(0)) { unavailablePort = unused.getLocalPort(); }
        try {
            var factory = ExternalHttpRequestFactory.simple(Duration.ofSeconds(1), Duration.ofSeconds(2),
                ExternalHttpRequestFactory.parseProxy("http://127.0.0.1:" + unavailablePort));
            var client = RestClient.builder().requestFactory(factory).build();
            assertThatThrownBy(() -> client.get().uri("http://127.0.0.1:" + origin.getAddress().getPort()).retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class);
            assertThat(originRequests).hasValue(0);
        } finally { origin.stop(0); }
    }

    @Test
    void httpsUsesConnectAndARejectedTunnelCannotSendToTheTarget() throws Exception {
        try (ServerSocket gateway = new ServerSocket(0); var worker = Executors.newSingleThreadExecutor()) {
            gateway.setSoTimeout(3000);
            var received = worker.submit(() -> {
                try (var socket = gateway.accept()) {
                    socket.setSoTimeout(3000);
                    var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String firstLine = reader.readLine();
                    while (!reader.readLine().isEmpty()) { /* bounded fixture headers */ }
                    socket.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    return firstLine;
                }
            });
            var factory = ExternalHttpRequestFactory.simple(Duration.ofSeconds(1), Duration.ofSeconds(2),
                ExternalHttpRequestFactory.parseProxy("http://127.0.0.1:" + gateway.getLocalPort()));
            var client = RestClient.builder().requestFactory(factory).build();
            assertThatThrownBy(() -> client.get().uri("https://fixture.invalid/never-sent").retrieve().toBodilessEntity())
                .isInstanceOf(ResourceAccessException.class);
            assertThat(received.get(3, TimeUnit.SECONDS)).startsWith("CONNECT fixture.invalid:443 ");
        }
    }

    @Test
    void proxyConfigurationRejectsSecretsAndAmbiguousAuthoritiesWithoutEchoingValues() {
        assertThat(ExternalHttpRequestFactory.parseProxy("  http://gateway:3128/ "))
            .isEqualTo(URI.create("http://gateway:3128"));
        for (String invalid : new String[] {"https://gateway:3128", "http://gateway", "http://user:fixture-secret@gateway:3128",
            "http://gateway:3128/path", "http://gateway:3128?bypass=true", "http://gateway:70000", "http://gateway:3128#fragment"}) {
            assertThatThrownBy(() -> ExternalHttpRequestFactory.parseProxy(invalid))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("REPOGUARD_OUTBOUND_HTTP_PROXY")
                .hasMessageNotContaining(invalid).hasMessageNotContaining("fixture-secret");
        }
    }

    @Test
    void simpleRejectsNonPositiveTimeouts() {
        assertThatThrownBy(() -> ExternalHttpRequestFactory.simple(Duration.ZERO, Duration.ofSeconds(1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("connectTimeout must be positive");
    }

    private HttpClient client(Object target) throws ReflectiveOperationException {
        return (HttpClient) field(target, "httpClient");
    }

    private Duration durationField(Object target, String fieldName) throws ReflectiveOperationException {
        return (Duration) field(target, fieldName);
    }

    private Object field(Object target, String fieldName) throws ReflectiveOperationException {
        Class<?> type = target.getClass();
        Field field = null;
        while (type != null && field == null) {
            try {
                field = type.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ex) {
                type = type.getSuperclass();
            }
        }
        if (field == null) {
            throw new NoSuchFieldException(fieldName);
        }
        field.setAccessible(true);
        return field.get(target);
    }
}
