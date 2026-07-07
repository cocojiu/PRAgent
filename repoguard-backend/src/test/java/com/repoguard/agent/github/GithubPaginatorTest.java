package com.repoguard.agent.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.config.GithubIntegrationSettings;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.external.ExternalHttpJsonResponseReader;
import com.repoguard.agent.external.ExternalHttpResponseReader;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

class GithubPaginatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExternalHttpResponseReader responseReader = new ExternalHttpResponseReader();
    private final ExternalHttpJsonResponseReader jsonResponseReader = new ExternalHttpJsonResponseReader(
        objectMapper,
        responseReader
    );

    @Test
    void failsWhenMaxPageStillLooksTruncated() throws Exception {
        try (PagedGithubServer server = startServer(250)) {
            GithubPaginator paginator = paginator();

            assertThatThrownBy(() -> paginator.fetchPages(
                "fetch_pull_request_diff",
                page -> server.baseUrl() + "/items?per_page=100&page=" + page,
                settings(),
                GithubChangedFile[].class,
                passthroughResilience()
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
            GithubPaginator paginator = paginator();

            List<GithubChangedFile> result = paginator.fetchPages(
                "fetch_pull_request_diff",
                page -> server.baseUrl() + "/items?per_page=100&page=" + page,
                settings(),
                GithubChangedFile[].class,
                passthroughResilience()
            );

            assertThat(result).hasSize(101);
            assertThat(result.get(100).filename()).isEqualTo("src/File101.java");
            assertThat(server.pageRequests()).containsExactly(1, 2);
        }
    }

    @Test
    void stopsOnFullLastPageWhenLinkHeaderHasNoNextPage() throws Exception {
        try (PagedGithubServer server = startServer(200, true)) {
            GithubPaginator paginator = paginator();

            List<GithubChangedFile> result = paginator.fetchPages(
                "fetch_pull_request_diff",
                page -> server.baseUrl() + "/items?per_page=100&page=" + page,
                settings(),
                GithubChangedFile[].class,
                passthroughResilience()
            );

            assertThat(result).hasSize(200);
            assertThat(result.get(199).filename()).isEqualTo("src/File200.java");
            assertThat(server.pageRequests()).containsExactly(1, 2);
        }
    }

    @Test
    void followsNextUrlFromLinkHeader() throws Exception {
        try (PagedGithubServer server = startServer(150, true)) {
            GithubPaginator paginator = paginator();

            List<GithubChangedFile> result = paginator.fetchPages(
                "fetch_pull_request_diff",
                page -> server.baseUrl() + "/items?per_page=100&page=" + page,
                settings(),
                GithubChangedFile[].class,
                passthroughResilience()
            );

            assertThat(result).hasSize(150);
            assertThat(server.pageRequests()).containsExactly(1, 2);
            assertThat(server.markerRequests()).containsExactly("", "from-link");
        }
    }

    @Test
    void rejectsMissingResilience() {
        GithubPaginator paginator = paginator();

        assertThatThrownBy(() -> paginator.fetchPages(
            "fetch_pull_request_diff",
            page -> "http://127.0.0.1/items?page=" + page,
            settings(),
            GithubChangedFile[].class,
            null
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("resilience");
    }

    @Test
    void usesSharedResponseReaderForHttpFailures() throws Exception {
        try (PagedGithubServer server = startServer(
            0,
            false,
            429,
            "{\"message\":\"rate limited\",\"token\":\"raw-token-value\"}",
            "60"
        )) {
            GithubPaginator paginator = paginator();

            assertThatThrownBy(() -> paginator.fetchPages(
                "fetch_pull_request_diff",
                page -> server.baseUrl() + "/items?per_page=100&page=" + page,
                settings(),
                GithubChangedFile[].class,
                passthroughResilience()
            ))
                .isInstanceOfSatisfying(RestClientResponseException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(429);
                    assertThat(ex.getResponseHeaders()).isNotNull();
                    assertThat(ex.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
                    assertThat(ex.getResponseBodyAsString())
                        .isEqualTo("{\"message\":\"rate limited\",\"token\":\"raw-token-value\"}");
                    assertThat(ex.getMessage()).contains("GitHub fetch_pull_request_diff failed with HTTP status 429");
                });
        }
    }

    private GithubPaginator paginator() {
        return new GithubPaginator(RestClient.builder(), jsonResponseReader, 2);
    }

    private ExternalCallResilience passthroughResilience() {
        ExternalCallResilience resilience = org.mockito.Mockito.mock(ExternalCallResilience.class);
        when(resilience.github(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(invocation -> {
                java.util.function.Supplier<?> supplier = invocation.getArgument(1);
                return supplier.get();
            });
        return resilience;
    }

    private PagedGithubServer startServer(int total) throws IOException {
        return startServer(total, false);
    }

    private PagedGithubServer startServer(int total, boolean includeLinkHeader) throws IOException {
        return startServer(total, includeLinkHeader, 200, null, null);
    }

    private PagedGithubServer startServer(
        int total,
        boolean includeLinkHeader,
        int status,
        String responseBody,
        String retryAfter
    ) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        List<Integer> pageRequests = new ArrayList<>();
        List<String> markerRequests = new ArrayList<>();
        server.createContext("/items", exchange -> {
            URI uri = exchange.getRequestURI();
            int page = queryInt(uri, "page", 1);
            int perPage = queryInt(uri, "per_page", 30);
            pageRequests.add(page);
            markerRequests.add(queryParam(uri, "marker"));
            byte[] bytes = (responseBody == null ? changedFilesJson(total, page, perPage) : responseBody)
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (retryAfter != null) {
                exchange.getResponseHeaders().set(HttpHeaders.RETRY_AFTER, retryAfter);
            }
            if (includeLinkHeader) {
                String linkHeader = linkHeader(baseUrl, total, page, perPage);
                if (!linkHeader.isBlank()) {
                    exchange.getResponseHeaders().set("Link", linkHeader);
                }
            }
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new PagedGithubServer(
            server,
            baseUrl,
            pageRequests,
            markerRequests
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

    private String queryParam(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return "";
        }
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && name.equals(parts[0])) {
                return parts[1];
            }
        }
        return "";
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

    private String linkHeader(String baseUrl, int total, int page, int perPage) {
        List<String> links = new ArrayList<>();
        if (page > 1) {
            links.add("<%s/items?per_page=%d&page=%d&marker=from-link>; rel=\"prev\""
                .formatted(baseUrl, perPage, page - 1));
        }
        if (page * perPage < total) {
            links.add("<%s/items?per_page=%d&page=%d&marker=from-link>; rel=\"next\""
                .formatted(baseUrl, perPage, page + 1));
        }
        return String.join(", ", links);
    }

    private record PagedGithubServer(
        HttpServer server,
        String baseUrl,
        List<Integer> pageRequests,
        List<String> markerRequests
    ) implements AutoCloseable {

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
