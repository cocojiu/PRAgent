package com.repoguard.agent.github.checks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.github.GithubAppProperties;
import com.repoguard.agent.github.GithubIntegrationSettings;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class GithubCheckRunGatewayTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void usesChecksApiPathsHeadersAndPayloads() throws Exception {
        AtomicReference<String> methods = new AtomicReference<>("");
        AtomicReference<String> payload = new AtomicReference<>("");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            methods.updateAndGet(value -> value + exchange.getRequestMethod() + " ");
            payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String body = exchange.getRequestMethod().equals("GET")
                ? "{\"check_runs\":[]}" : "{\"id\":99,\"external_id\":\"repoguard-task:7:run:1\",\"status\":\"queued\"}";
            write(exchange, 200, body);
        });
        server.start();
        GithubAppProperties appProperties = new GithubAppProperties();
        appProperties.setApiVersion("2022-11-28");
        GithubCheckRunGateway gateway = new GithubCheckRunGateway(RestClient.builder(), new ObjectMapper(), appProperties);
        GithubIntegrationSettings settings = new GithubIntegrationSettings(
            "GITHUB", "CONFIGURED", baseUrl(), "ghp_test", null, "octo", "repo", 1L
        );

        GithubCheckRunGateway.RemoteCheckRun created = gateway.create(
            settings, baseUrl(), "octo", "repo",
            new GithubCheckRunGateway.CreateRequest(
                "RepoGuard PR Review", "0123456789abcdef0123456789abcdef01234567", "queued",
                "repoguard-task:7:run:1",
                new GithubCheckRunGateway.Output("title", "summary", null, List.of())
            )
        );
        GithubCheckRunGateway.RemoteCheckRun found = gateway.find(
            settings, baseUrl(), "octo", "repo", "0123456789abcdef0123456789abcdef01234567",
            "RepoGuard PR Review", "repoguard-task:7:run:1"
        );

        assertThat(created.id()).isEqualTo(99L);
        assertThat(found).isNull();
        assertThat(methods.get()).contains("POST", "GET");
        assertThat(payload.get()).isEqualTo("");
    }

    @Test
    void findsMatchingRunAndReturnsNullForMalformedRunCollection() throws Exception {
        AtomicReference<Boolean> malformed = new AtomicReference<>(false);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = malformed.get()
                ? "{\"check_runs\":{}}"
                : "{\"check_runs\":[{\"id\":101,\"external_id\":\"target\",\"status\":\"completed\",\"conclusion\":\"success\"}]}";
            write(exchange, 200, body);
        });
        server.start();
        GithubCheckRunGateway gateway = gateway();
        GithubIntegrationSettings settings = settings();

        GithubCheckRunGateway.RemoteCheckRun found = gateway.find(
            settings, baseUrl(), "octo", "repo", "sha", "RepoGuard PR Review", "target"
        );
        assertThat(found).isNotNull();
        assertThat(found.id()).isEqualTo(101L);
        assertThat(found.conclusion()).isEqualTo("success");

        malformed.set(true);
        assertThat(gateway.find(
            settings, baseUrl(), "octo", "repo", "sha", "RepoGuard PR Review", "target"
        )).isNull();
    }

    @Test
    void writesOptionalUpdateFieldsAndAnnotations() throws Exception {
        AtomicReference<String> postPayload = new AtomicReference<>();
        AtomicReference<String> patchPayload = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if ("POST".equals(exchange.getRequestMethod())) {
                postPayload.set(body);
            } else if ("PATCH".equals(exchange.getRequestMethod())) {
                patchPayload.set(body);
            }
            write(exchange, 200, "{\"id\":99,\"external_id\":\"external\",\"status\":\"completed\",\"conclusion\":\"success\"}");
        });
        server.start();
        GithubCheckRunGateway gateway = gateway();
        GithubIntegrationSettings settings = settings();
        GithubCheckRunGateway.Annotation annotation = new GithubCheckRunGateway.Annotation(
            "src/Foo.java", 3, 4, "failure", "bad code", "RG-1", "use a safer API"
        );
        GithubCheckRunGateway.Output output = new GithubCheckRunGateway.Output(
            "title", "summary", "details", List.of(annotation)
        );

        gateway.create(settings, baseUrl(), "octo", "repo",
            new GithubCheckRunGateway.CreateRequest("name", "sha", "queued", "external", output));
        gateway.update(settings, baseUrl(), "octo", "repo", 99L,
            new GithubCheckRunGateway.UpdateRequest("completed", "success", "2026-09-01T00:00:00Z",
                "2026-09-01T00:01:00Z", output));

        assertThat(authorization.get()).isEqualTo("Bearer ghp_test");
        assertThat(postPayload.get()).contains("\"name\":\"name\"", "\"head_sha\":\"sha\"", "\"annotations\"");
        assertThat(patchPayload.get()).contains(
            "\"conclusion\":\"success\"", "\"started_at\":\"2026-09-01T00:00:00Z\"",
            "\"completed_at\":\"2026-09-01T00:01:00Z\"", "\"raw_details\":\"use a safer API\"",
            "\"text\":\"details\""
        );
    }

    @Test
    void rejectsMissingTokenAndInvalidJsonOrRemoteId() throws Exception {
        GithubCheckRunGateway gateway = gateway();
        GithubIntegrationSettings missingToken = new GithubIntegrationSettings(
            "GITHUB", "CONFIGURED", "https://api.github.com", " ", null, "octo", "repo", 1L
        );
        assertThatThrownBy(() -> gateway.find(
            missingToken, "https://api.github.com", "octo", "repo", "sha", "name", "external"
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("token");

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> write(exchange, 200, "not-json"));
        server.start();
        assertThatThrownBy(() -> gateway.find(
            settings(), baseUrl(), "octo", "repo", "sha", "name", "external"
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("not valid JSON");

        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> write(exchange, 200, "{\"id\":\"bad\"}"));
        server.start();
        assertThatThrownBy(() -> gateway.create(
            settings(), baseUrl(), "octo", "repo",
            new GithubCheckRunGateway.CreateRequest("name", "sha", "queued", "external",
                new GithubCheckRunGateway.Output("title", "summary", null, List.of()))
        )).isInstanceOf(IllegalStateException.class).hasMessageContaining("check run id");
    }

    private GithubCheckRunGateway gateway() {
        GithubAppProperties appProperties = new GithubAppProperties();
        appProperties.setApiVersion("2022-11-28");
        return new GithubCheckRunGateway(RestClient.builder(), new ObjectMapper(), appProperties);
    }

    private GithubIntegrationSettings settings() {
        return new GithubIntegrationSettings(
            "GITHUB", "CONFIGURED", baseUrl(), "ghp_test", null, "octo", "repo", 1L
        );
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
