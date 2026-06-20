package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.security.SecretCryptoService;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class GithubConnectionProbeTest {

    private final SecretCryptoService secretCryptoService = new SecretCryptoService("test-encryption-key");
    private final GithubConnectionProbe probe = new GithubConnectionProbe(RestClient.builder(), secretCryptoService);

    @Test
    void probeUsesRateLimitEndpointWhenRepositoryIsMissing() throws Exception {
        try (ProbeServer server = startProbeServer()) {
            IntegrationConfig config = githubConfig(server.baseUrl());

            probe.probe(config);

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

    private IntegrationConfig githubConfig(String baseUrl) {
        IntegrationConfig config = new IntegrationConfig();
        config.setProvider("GITHUB");
        config.setBaseUrl(baseUrl);
        config.setTokenValue(secretCryptoService.encrypt("ghp_test_1234"));
        return config;
    }

    private ProbeServer startProbeServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> path = new AtomicReference<>("");
        AtomicReference<String> authorization = new AtomicReference<>("");
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
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
