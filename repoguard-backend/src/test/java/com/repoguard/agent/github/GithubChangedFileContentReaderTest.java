package com.repoguard.agent.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.external.ExternalHttpResponseReader;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class GithubChangedFileContentReaderTest {

    @Test
    void readsRawContentAtExactHeadWithEncodedRepositoryPath() throws Exception {
        AtomicReference<String> requestTarget = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> accept = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestTarget.set(exchange.getRequestURI().toASCIIString());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            accept.set(exchange.getRequestHeaders().getFirst("Accept"));
            byte[] body = "class Admin Controller {}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            GithubChangedFileContentReader reader = new GithubChangedFileContentReader(
                RestClient.builder(),
                new ExternalHttpResponseReader()
            );
            GithubIntegrationSettings settings = new GithubIntegrationSettings(
                "GITHUB",
                "CONFIGURED",
                null,
                "raw-token",
                null,
                null,
                null,
                1L
            );

            String content = reader.fetch(
                settings,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "octocat",
                "repo",
                "abc123",
                "src/main/java/My File.java",
                passthroughResilience()
            );

            assertThat(content).isEqualTo("class Admin Controller {}");
            assertThat(requestTarget.get())
                .isEqualTo("/repos/octocat/repo/contents/src/main/java/My%20File.java?ref=abc123");
            assertThat(authorization.get()).isEqualTo("Bearer raw-token");
            assertThat(accept.get()).contains("application/vnd.github.raw+json");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsRepositoryPathTraversalBeforeCallingGithub() {
        GithubChangedFileContentReader reader = new GithubChangedFileContentReader(
            RestClient.builder(),
            new ExternalHttpResponseReader()
        );

        assertThatThrownBy(() -> reader.fetch(
            GithubIntegrationSettings.empty(),
            "https://api.github.test",
            "owner",
            "repo",
            "abc123",
            "../secret.txt",
            passthroughResilience()
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalid segment");
    }

    private ExternalCallResilience passthroughResilience() {
        ExternalCallResilience resilience = mock(ExternalCallResilience.class);
        when(resilience.github(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(invocation -> {
                java.util.function.Supplier<?> supplier = invocation.getArgument(1);
                return supplier.get();
            });
        return resilience;
    }
}
