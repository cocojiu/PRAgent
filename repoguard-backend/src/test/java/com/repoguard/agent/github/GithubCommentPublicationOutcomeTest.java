package com.repoguard.agent.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.external.ExternalCallResilienceConfig;
import com.repoguard.agent.external.ExternalCallResilienceProperties;
import com.repoguard.agent.external.ExternalCallRetryMetricsRecorder;
import com.repoguard.agent.external.ExternalHttpJsonResponseReader;
import com.repoguard.agent.external.ExternalHttpResponseReader;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

class GithubCommentPublicationOutcomeTest {
    private final GithubCommentPublicationGateway gateway = new GithubCommentPublicationGateway(
        RestClient.builder(), new ExternalHttpJsonResponseReader(new ObjectMapper(), new ExternalHttpResponseReader()));
    private final GithubIntegrationSettings settings = new GithubIntegrationSettings(
        "GITHUB", "CONNECTED", null, "local-fixture", null, null, null, null);

    @ParameterizedTest
    @ValueSource(strings = {"comment", "line", "review"})
    void acceptedWriteWithGatewayErrorIsNotRepeated(String kind) throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int count = accepted.incrementAndGet();
            byte[] response = (count == 1 ? "{\"message\":\"response unavailable after commit\"}"
                : "{\"id\":123,\"html_url\":\"https://example.invalid/123\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(count == 1 ? 503 : 201, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            assertThatThrownBy(() -> publish(kind, url, resilience())).hasMessageContaining("github_write_outcome_unknown");
            assertThat(accepted).hasValue(1);
        } finally {
            server.stop(0);
        }
    }


    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "{bad-json"})
    void acceptedWriteWithUnreadableIdentityRequiresVerification(String body) throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            accepted.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, bytes.length == 0 ? -1 : bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            assertThatThrownBy(() -> publish("comment", url(server), resilience()))
                .hasMessageContaining("github_write_outcome_unknown");
            assertThat(accepted).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acceptedWriteWithLostAcknowledgementIsNotRepeated() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            accepted.incrementAndGet();
            exchange.close();
        });
        server.start();
        try {
            assertThatThrownBy(() -> publish("comment", url(server), resilience()))
                .hasMessageContaining("github_write_outcome_unknown");
            assertThat(accepted).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acceptedWriteWithReadTimeoutIsNotRepeated() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            accepted.incrementAndGet();
            try {
                // Exercise the actual production 30-second read timeout, not a thrown mock exception.
                Thread.sleep(35_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            assertThatThrownBy(() -> publish("comment", url(server), resilience()))
                .hasMessageContaining("github_write_outcome_unknown");
            assertThat(accepted).hasValue(1);
        } finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void safeReadStillRetriesTransientFailure() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            int count = requests.incrementAndGet();
            byte[] bytes = (count == 1 ? "{}" : "[]").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(count == 1 ? 503 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            assertThat(gateway.listReviewComments(url(server), settings, resilience())).isEmpty();
            assertThat(requests).hasValue(2);
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"summary", "review", "fallback", "superseded"})
    void writerRetainsUnknownStatusAcrossPublicationStrategies(String kind) throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger prComments = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            boolean post = "POST".equals(exchange.getRequestMethod());
            exchange.getRequestBody().readAllBytes();
            int status = 200;
            String body = "{\"head\":{\"sha\":\"fixture-sha\"},\"updated_at\":\"2026-10-19T00:00:00Z\"}";
            if (!post && "superseded".equals(kind)) body = "{\"head\":{\"sha\":\"new-head\"},\"updated_at\":\"2026-10-19T00:00:00Z\"}";
            if (post) {
                if (path.endsWith("/reviews") && "fallback".equals(kind)) {
                    status = 422;
                    body = "{\"message\":\"Validation Failed\"}";
                } else {
                    accepted.incrementAndGet();
                    if (path.contains("/issues/")) prComments.incrementAndGet();
                    status = 503;
                    body = "{}";
                }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var writer = new GithubCommentWriter(RestClient.builder(), mock(GithubIntegrationHealthReporter.class),
                new ExternalHttpJsonResponseReader(new ObjectMapper(), new ExternalHttpResponseReader()));
            var task = new com.repoguard.agent.entity.ReviewTask();
            task.setId(1L);
            task.setPrNumber(1);
            task.setCommitSha("fixture-sha");
            var draft = "summary".equals(kind)
                ? new GithubReviewCommentDraft(null, null, null, "fixture", "pull_request")
                : new GithubReviewCommentDraft(10L, "src/App.java", 1, "fixture", "line");
            var results = writer.publishPullRequestComments(settings, url(server), "fixture", "repo",
                task, List.of(draft), resilience());
            assertThat(results).hasSize(1);
            assertThat(results.getFirst().success()).isFalse();
            assertThat(results.getFirst().status()).as(results.getFirst().message()).isEqualTo("outcome_unknown");
            assertThat(accepted).hasValue(1);
            if ("fallback".equals(kind) || "review".equals(kind)) assertThat(prComments).hasValue(0);
        } finally {
            server.stop(0);
        }
    }


    @Test
    void ambiguousWriteStillContributesToCircuitBreakerFailure() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            accepted.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
        try {
            var properties = new ExternalCallResilienceProperties();
            properties.getGithub().setCircuitBreakerMinimumCalls(1);
            var resilience = new ExternalCallResilienceConfig()
                .externalCallResilience(properties, mock(ExternalCallRetryMetricsRecorder.class));
            assertThatThrownBy(() -> publish("comment", url(server), resilience))
                .hasMessageContaining("github_write_outcome_unknown");
            assertThatThrownBy(() -> publish("comment", url(server), resilience))
                .hasMessageContaining("github_circuit_open");
            assertThat(accepted).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    private String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private Object publish(String kind, String url, ExternalCallResilience resilience) {
        GithubReviewCommentDraft draft = new GithubReviewCommentDraft(10L, "src/App.java", 1, "fixture", "line");
        return switch (kind) {
            case "comment" -> gateway.publishPullRequestComment(url, "fixture", settings, resilience);
            case "line" -> gateway.publishLineComment(url, draft, "fixture-sha", settings, resilience);
            case "review" -> gateway.publishReview(url, List.of(draft), "fixture-sha", settings, resilience);
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private ExternalCallResilience resilience() {
        ExternalCallResilienceProperties properties = new ExternalCallResilienceProperties();
        properties.getGithub().setRetryMaxAttempts(2);
        properties.getGithub().setRetryWaitMillis(1);
        properties.getGithub().setRetryMaxWaitMillis(1);
        return new ExternalCallResilienceConfig().externalCallResilience(properties, mock(ExternalCallRetryMetricsRecorder.class));
    }
}