package com.repoguard.agent.github;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.repoguard.agent.config.JacksonConfig;
import com.repoguard.agent.external.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class GithubPullRequestPathsReaderTest {
    private final String head = "a".repeat(40), base = "b".repeat(40);
    private HttpServer server;
    private String url, afterHead, afterBase, afterUpdated, link;
    private IntFunction<String> pageBody;
    private int fileStatus;
    private final AtomicInteger metadataReads = new AtomicInteger();
    private final AtomicLong time = new AtomicLong();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final ExternalCallResilience resilience = mock(ExternalCallResilience.class);
    private final OutboundEndpointPolicy policy = mock(OutboundEndpointPolicy.class);
    private GithubPullRequestPathsReader reader;
    private final GithubIntegrationSettings settings = GithubIntegrationSettings.empty();
    private boolean expireOnFiles, expireOnEmpty;
    @BeforeEach void start() throws Exception {
        afterHead = head; afterBase = base; afterUpdated = "2026-01-01T00:00:00Z"; fileStatus = 200;
        pageBody = page -> page == 1 ? "[{\"filename\":\"new/a\",\"status\":\"renamed\",\"previous_filename\":\"old/a\",\"patch\":\"ignored\"}]" : "[]";
        var json = new JacksonConfig().objectMapper();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI().toString()); boolean metadata = exchange.getRequestURI().getPath().endsWith("/pulls/7");
            String body;
            if (metadata) {
                boolean first = metadataReads.getAndIncrement() == 0;
                body = "{\"head\":{\"sha\":\"" + (first ? head : afterHead) + "\"},\"base\":{\"sha\":\"" + (first ? base : afterBase)
                    + "\"},\"updated_at\":\"" + (first ? "2026-01-01T00:00:00Z" : afterUpdated) + "\"}";
            } else {
                int page = Integer.parseInt(exchange.getRequestURI().getQuery().replaceAll(".*(?:^|&)page=(\\d+).*", "$1"));
                body = pageBody.apply(page); if (link != null) exchange.getResponseHeaders().set("Link", link);
                if (expireOnFiles || expireOnEmpty && body.equals("[]")) time.set(java.util.concurrent.TimeUnit.SECONDS.toNanos(60));
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(metadata ? 200 : fileStatus, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        }); server.start(); url = "http://127.0.0.1:" + server.getAddress().getPort();
        when(resilience.github(anyString(), any())).thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(1)).get());
        when(policy.validate(any(), anyString())).thenAnswer(call -> URI.create(call.getArgument(1)));
        when(policy.sameOrigin(any(), anyString(), anyString())).thenAnswer(call -> {
            URI original = URI.create(call.getArgument(1)), next = URI.create(call.getArgument(2));
            return original.getScheme().equals(next.getScheme()) && original.getAuthority().equals(next.getAuthority());
        });
        var responses = new ExternalHttpJsonResponseReader(json, new ExternalHttpResponseReader());
        reader = new GithubPullRequestPathsReader(new GithubPaginator(RestClient.builder(), responses, policy),
            new GithubPullRequestHeadReader(RestClient.builder(), responses, policy), time::get);
    }
    @AfterEach void stop() { server.stop(0); }
    private GithubPullRequestPathsReader.Paths read() { return reader.fetch(settings, url, "owner", "repo", 7, head, base, resilience); }
    private String page(int page, int total) {
        return java.util.stream.IntStream.range((page - 1) * 100, Math.min(page * 100, total))
            .mapToObj(n -> "{\"filename\":\"f" + n + "\",\"status\":\"modified\"}")
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }
    @Test void realHttpCapturesOldPathAndChecksVersionsBeforeAndAfter() {
        var result = read(); assertThat(result.status()).isEqualTo("COMPLETE");
        assertThat(result.files().get("new/a").previousFilename()).isEqualTo("old/a");
        assertThat(requests).containsExactly("/repos/owner/repo/pulls/7", "/repos/owner/repo/pulls/7/files?per_page=100&page=1", "/repos/owner/repo/pulls/7");
        assertThatThrownBy(() -> result.files().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void exactlyTwoHundredFilesRequireTheEmptyThirdPageWhenLinkIsAbsent() {
        pageBody = page -> page(page, 200); assertThat(read().files()).hasSize(200);
        assertThat(requests).contains("/repos/owner/repo/pulls/7/files?per_page=100&page=3"); assertThat(metadataReads).hasValue(2);
    }
    @Test void doesNotReturnPartialPathsWhenTheFileBudgetIsExceeded() {
        pageBody = page -> page(page, 201); var result = read();
        assertThat(result.status()).isEqualTo("BUDGET_EXCEEDED"); assertThat(result.files()).isEmpty(); assertThat(metadataReads).hasValue(1);
    }
    @Test void rejectsMissingUnsafeAndIdenticalOldPaths() {
        for (String old : List.of("null", "\"../secret\"", "\"new/a\"", "\"old//a\"")) {
            metadataReads.set(0); pageBody = page -> "[{\"filename\":\"new/a\",\"status\":\"renamed\",\"previous_filename\":" + old + "}]";
            assertThat(read().status()).isEqualTo("INVALID_CHANGES");
        }
    }
    @Test void rejectsDuplicatePathsAcrossPages() {
        pageBody = page -> page == 1 ? page(1, 100) : "[{\"filename\":\"f0\",\"status\":\"modified\"}]";
        assertThat(read().status()).isEqualTo("INVALID_CHANGES");
    }
    @Test void rejectsHeadBaseAndUpdatedAtChangesAfterReading() {
        for (int change = 0; change < 3; change++) {
            metadataReads.set(0); afterHead = head; afterBase = base; afterUpdated = "2026-01-01T00:00:00Z";
            switch (change) { case 0 -> afterHead = "c".repeat(40); case 1 -> afterBase = "c".repeat(40); case 2 -> afterUpdated = "2026-01-01T00:00:01Z"; default -> throw new AssertionError(); }
            var result = read(); assertThat(result.status()).isEqualTo("TASK_CHANGED"); assertThat(result.files()).isEmpty();
        }
    }
    @Test void refusesUntrustedVersionBeforeRequestingFiles() {
        assertThat(reader.fetch(settings, url, "owner", "repo", 7, "main", base, resilience).status()).isEqualTo("TASK_CHANGED");
        assertThat(requests).isEmpty();
        assertThat(reader.fetch(settings, url, "owner", "repo", 7, "c".repeat(40), base, resilience).status()).isEqualTo("TASK_CHANGED");
        assertThat(requests).hasSize(1);
    }
    @Test void enforcesTheMonotonicTimeBudgetWithoutSleeping() {
        expireOnFiles = true; var result = read(); assertThat(result.status()).isEqualTo("BUDGET_EXCEEDED");
        assertThat(result.files()).isEmpty(); assertThat(metadataReads).hasValue(1);
    }
    @Test void neverFollowsAnOffOriginPaginationLink() {
        link = "<http://127.0.0.1:9/leak>; rel=\"next\"";
        assertThatThrownBy(this::read).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("origin changed");
        assertThat(requests).hasSize(2);
    }
    @Test void keepsAuthorizationErrorsVisibleAndRejectsMalformedStatus() {
        fileStatus = 403;
        assertThatThrownBy(this::read).isInstanceOf(org.springframework.web.client.RestClientResponseException.class)
            .satisfies(failure -> assertThat(((org.springframework.web.client.RestClientResponseException) failure).getStatusCode().value()).isEqualTo(403));
        assertThat(requests).hasSize(2);
        fileStatus = 200; metadataReads.set(0); pageBody = page -> "[{\"filename\":\"a\",\"status\":null}]";
        assertThat(read().status()).isEqualTo("INVALID_CHANGES");
    }
    @Test void boundsRetainedUtf8MetadataAndUnexpectedOversizedPages() {
        pageBody = page -> java.util.stream.IntStream.range((page - 1) * 100, page * 100).mapToObj(n ->
            "{\"filename\":\"n" + n + "界".repeat(508) + "\",\"status\":\"renamed\",\"previous_filename\":\"o" + n + "界".repeat(508) + "\"}")
            .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        assertThat(read().status()).isEqualTo("BUDGET_EXCEEDED");
        metadataReads.set(0); pageBody = page -> page(1, 101).replace("]", ",{\"filename\":\"extra\",\"status\":\"modified\"}]");
        assertThat(read().status()).isEqualTo("BUDGET_EXCEEDED");
    }
    @Test void expiryOnTheEmptySentinelPageSkipsFurtherMetadataIo() {
        pageBody = page -> page(page, 200); expireOnEmpty = true;
        var result = read(); assertThat(result.status()).isEqualTo("BUDGET_EXCEEDED"); assertThat(result.files()).isEmpty();
        assertThat(metadataReads).hasValue(1);
    }
}
