package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.external.ExternalHttpResponseReader;
import com.repoguard.agent.security.SecretCryptoService;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

class GithubConnectionProbeTest {

    private final SecretCryptoService secretCryptoService = new SecretCryptoService("test-encryption-key");
    private final GithubConnectionProbe probe = new GithubConnectionProbe(
        RestClient.builder(),
        secretCryptoService,
        new ExternalHttpResponseReader()
    );

    @Test
    void providerReturnsGithubProviderCode() {
        assertThat(probe.provider()).isEqualTo("GITHUB");
    }

    @Test
    void probeUsesRateLimitEndpointWhenRepositoryIsMissing() throws Exception {
        try (ProbeServer server = startProbeServer()) {
            IntegrationConfig config = githubConfig(server.baseUrl());

            ConnectionProbeResult result = probe.probe(config);

            assertThat(result.healthy()).isTrue();
            assertThat(result.status()).isEqualTo("connected");
            assertThat(server.path()).isEqualTo("/rate_limit");
            assertThat(server.authorization()).isEqualTo("Bearer ghp_test_1234");
        }
    }

    @Test
    void probeUsesRepositoryEndpointWhenRepositoryIsConfigured() throws Exception {
        try (ProbeServer server = startProbeServer()) {
            IntegrationConfig config = githubConfig(server.baseUrl());
            config.setDefaultOwner("repo-guard");
            config.setDefaultRepo("agent");

            probe.probe(config);

            assertThat(server.path()).isEqualTo("/repos/repo-guard/agent");
            assertThat(server.authorization()).isEqualTo("Bearer ghp_test_1234");
        }
    }

    @Test
    void probeUsesSharedResponseReaderForHttpFailures() throws Exception {
        try (ProbeServer server = startProbeServer(
            429,
            "{\"message\":\"rate limited\",\"token\":\"raw-token-value\"}",
            "30"
        )) {
            IntegrationConfig config = githubConfig(server.baseUrl());

            assertThatThrownBy(() -> probe.probe(config))
                .isInstanceOfSatisfying(RestClientResponseException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(429);
                    assertThat(ex.getResponseHeaders()).isNotNull();
                    assertThat(ex.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
                    assertThat(ex.getResponseBodyAsString())
                        .isEqualTo("{\"message\":\"rate limited\",\"token\":\"raw-token-value\"}");
                    assertThat(ex.getMessage()).contains("GitHub connection test failed with HTTP status 429");
                });
        }
    }

    private IntegrationConfig githubConfig(String baseUrl) {
        IntegrationConfig config = new IntegrationConfig();
        config.setProvider("GITHUB");
        config.setBaseUrl(baseUrl);
        config.setTokenValue(secretCryptoService.encrypt("ghp_test_1234"));
        return config;
    }

    private ProbeServer startProbeServer() throws IOException {
        return startProbeServer(200, "{}", null);
    }

    private ProbeServer startProbeServer(int status, String body, String retryAfter) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> path = new AtomicReference<>("");
        AtomicReference<String> authorization = new AtomicReference<>("");
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (retryAfter != null) {
                exchange.getResponseHeaders().set(HttpHeaders.RETRY_AFTER, retryAfter);
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new ProbeServer(
            server,
            "http://127.0.0.1:" + server.getAddress().getPort(),
            path,
            authorization
        );
    }

    private record ProbeServer(
        HttpServer server,
        String baseUrl,
        AtomicReference<String> pathRef,
        AtomicReference<String> authorizationRef
    ) implements AutoCloseable {

        @Override
        public void close() {
            server.stop(0);
        }

        String path() {
            return pathRef.get();
        }

        String authorization() {
            return authorizationRef.get();
        }
    }
}
