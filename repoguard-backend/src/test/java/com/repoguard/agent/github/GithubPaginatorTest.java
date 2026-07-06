package com.repoguard.agent.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.config.GithubIntegrationSettings;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class GithubPaginatorTest {

    @Test
    void failsWhenMaxPageStillLooksTruncated() throws Exception {
        try (PagedGithubServer server = startServer(250)) {
            GithubPaginator paginator = new GithubPaginator(RestClient.builder(), 2);

            assertThatThrownBy(() -> paginator.fetchPages(
                "fetch_pull_request_diff",
                page -> server.baseUrl() + "/items?per_page=100&page=" + page,
                settings(),
                GithubChangedFile[].class,
                null
            ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GitHub pagination limit reached")
                .hasMessageContaining("operation=fetch_pull_request_diff")
                .hasMessageContaining("pages=2");

            assertThat(server.pageRequests()).containsExactly(1, 2);
        }
    }

    @Test
    void returnsItemsWhenLastPageIsPartialBeforeLimit() throws Exception {
        try (PagedGithubServer server = startServer(101)) {
            GithubPaginator paginator = new GithubPaginator(RestClient.builder(), 2);

            List<GithubChangedFile> result = paginator.fetchPages(
                "fetch_pull_request_diff",
                page -> server.baseUrl() + "/items?per_page=100&page=" + page,
                settings(),
                GithubChangedFile[].class,
                null
            );

            assertThat(result).hasSize(101);
            assertThat(result.get(100).filename()).isEqualTo("src/File101.java");
            assertThat(server.pageRequests()).containsExactly(1, 2);
        }
    }

    private PagedGithubServer startServer(int total) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<Integer> pageRequests = new ArrayList<>();
        server.createContext("/items", exchange -> {
            URI uri = exchange.getRequestURI();
            int page = queryInt(uri, "page", 1);
            int perPage = queryInt(uri, "per_page", 30);
            pageRequests.add(page);
            byte[] bytes = changedFilesJson(total, page, perPage).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new PagedGithubServer(
            server,
            "http://127.0.0.1:" + server.getAddress().getPort(),
            pageRequests
        );
    }

    private GithubIntegrationSettings settings() {
        return new GithubIntegrationSettings(
            "GITHUB",
            "CONFIGURED",
            "http://127.0.0.1",
            "ghp_test",
            null,
            "octocat",
            "api",
            7L
        );
    }

    private int queryInt(URI uri, String name, int defaultValue) {
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return defaultValue;
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && name.equals(parts[0])) {
                return Integer.parseInt(parts[1]);
            }
        }
        return defaultValue;
    }

    private String changedFilesJson(int total, int page, int perPage) {
        int start = (page - 1) * perPage + 1;
        int end = Math.min(total, page * perPage);
        List<String> items = new ArrayList<>();
        for (int i = start; i <= end; i++) {
            items.add("""
                {"filename":"src/File%03d.java","status":"modified","additions":1,"deletions":0,"patch":"@@ patch %03d"}
                """.formatted(i, i).trim());
        }
        return "[" + String.join(",", items) + "]";
    }

    private record PagedGithubServer(
        HttpServer server,
        String baseUrl,
        List<Integer> pageRequests
    ) implements AutoCloseable {

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
