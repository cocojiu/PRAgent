package com.repoguard.agent.github;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.repoguard.agent.external.*;
import com.repoguard.agent.config.JacksonConfig;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class GithubCodeownersSourceTest {
    private static final String HEAD = "a".repeat(40), BASE = "b".repeat(40);
    private final GithubIntegrationSettings settings = GithubIntegrationSettings.empty();
    private final ExternalCallResilience resilience = mock(ExternalCallResilience.class);
    private final GithubChangedFileContentReader content = mock(GithubChangedFileContentReader.class);
    private final GithubPullRequestHeadReader reader = mock(GithubPullRequestHeadReader.class, CALLS_REAL_METHODS);
    private final GithubPullRequestHeadReader.GithubPullRequestHeadSnapshot version =
        new GithubPullRequestHeadReader.GithubPullRequestHeadSnapshot(HEAD, LocalDateTime.of(2026, 1, 1, 0, 0), BASE);

    private void stable() {
        doReturn(version).when(reader).fetchHead(settings, "https://api.github.com", "owner", "repo", 7, resilience);
    }
    private GithubPullRequestHeadReader.CodeownersSource read() {
        return reader.fetchCodeowners(settings, "https://api.github.com", "owner", "repo", 7, HEAD, resilience, content);
    }
    private void body(String path, String value) {
        when(content.fetch(settings, "https://api.github.com", "owner", "repo", BASE, path, resilience)).thenReturn(value);
    }
    private void failure(String path, int status) {
        when(content.fetch(settings, "https://api.github.com", "owner", "repo", BASE, path, resilience))
            .thenThrow(new ExternalCallException("github", "http", false, status, "fixture", null));
    }
    @Test void usesFirstExistingFileAndImmutableBase() {
        stable(); body(".github/CODEOWNERS", "* @owner");
        var result = read();
        assertThat(result.baseSha()).isEqualTo(BASE);
        assertThat(result.path()).isEqualTo(".github/CODEOWNERS");
        assertThat(result.status()).isEqualTo("AVAILABLE");
        verify(content).fetch(settings, "https://api.github.com", "owner", "repo", BASE, ".github/CODEOWNERS", resilience);
        verifyNoMoreInteractions(content);
    }
    @Test void fallsBackOnlyOn404AndPreservesEmptyFirstFile() {
        stable(); failure(".github/CODEOWNERS", 404); body("CODEOWNERS", "");
        assertThat(read().path()).isEqualTo("CODEOWNERS");
        verify(content, never()).fetch(any(), anyString(), anyString(), anyString(), anyString(), eq("docs/CODEOWNERS"), any());
    }
    @Test void allAbsentIsMissing() {
        stable();
        for (String path : List.of(".github/CODEOWNERS", "CODEOWNERS", "docs/CODEOWNERS")) failure(path, 404);
        assertThat(read().status()).isEqualTo("MISSING");
    }
    @Test void doesNotHideAuthorizationOrRateLimitFailures() {
        stable();
        for (int status : List.of(401, 403, 429, 500)) {
            reset(content); failure(".github/CODEOWNERS", status);
            assertThatThrownBy(this::read).isInstanceOf(ExternalCallException.class);
            verify(content, never()).fetch(any(), anyString(), anyString(), anyString(), anyString(), eq("CODEOWNERS"), any());
        }
    }
    @Test void boundsUtf8BytesAndLinesWithoutUsingLowerPriorityRules() {
        stable();
        for (String oversized : List.of("界".repeat(44000), "* @owner\n".repeat(2001))) {
            body(".github/CODEOWNERS", oversized);
            assertThat(read().status()).isEqualTo("BUDGET_EXCEEDED");
            assertThat(read().content()).isNull();
        }
    }
    @Test void rejectsHeadOrBaseChangesAfterRead() {
        for (var changed : List.of(
            new GithubPullRequestHeadReader.GithubPullRequestHeadSnapshot("c".repeat(40), version.updatedAt(), BASE),
            new GithubPullRequestHeadReader.GithubPullRequestHeadSnapshot(HEAD, version.updatedAt(), "c".repeat(40)),
            new GithubPullRequestHeadReader.GithubPullRequestHeadSnapshot(HEAD, version.updatedAt().plusSeconds(1), BASE))) {
            doReturn(version, changed).when(reader).fetchHead(settings, "https://api.github.com", "owner", "repo", 7, resilience);
            body(".github/CODEOWNERS", "* @owner");
            assertThatThrownBy(this::read).isInstanceOf(IllegalStateException.class).hasMessageContaining("changed");
        }
    }
    @Test void rejectsUntrustedOrMissingVersionBeforeContentRequest() {
        assertThatThrownBy(() -> reader.fetchCodeowners(settings, "url", "owner", "repo", 7, "main", resilience, content))
            .isInstanceOf(IllegalArgumentException.class);
        for (var invalid : List.of(
            new GithubPullRequestHeadReader.GithubPullRequestHeadSnapshot(HEAD, version.updatedAt()),
            new GithubPullRequestHeadReader.GithubPullRequestHeadSnapshot("c".repeat(40), version.updatedAt(), BASE),
            new GithubPullRequestHeadReader.GithubPullRequestHeadSnapshot(HEAD, version.updatedAt(), "main"))) {
            doReturn(invalid).when(reader).fetchHead(settings, "https://api.github.com", "owner", "repo", 7, resilience);
            assertThatThrownBy(this::read).isInstanceOf(IllegalStateException.class);
        }
        verifyNoInteractions(content);
    }
    @Test void realHttpReadsMetadataAndOnlyBaseContent() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> requests = new ArrayList<>();
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            String response = exchange.getRequestURI().getPath().endsWith("/pulls/7")
                ? "{\"head\":{\"sha\":\"" + HEAD + "\"},\"base\":{\"sha\":\"" + BASE + "\"},\"updated_at\":\"2026-01-01T00:00:00Z\"}"
                : "* @owner";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            when(resilience.github(any(), any())).thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(1)).get());
            var responseReader = new ExternalHttpResponseReader();
            var actual = new GithubPullRequestHeadReader(RestClient.builder(),
                new ExternalHttpJsonResponseReader(new JacksonConfig().objectMapper(), responseReader));
            var actualContent = new GithubChangedFileContentReader(RestClient.builder(), responseReader);
            var result = actual.fetchCodeowners(settings, "http://127.0.0.1:" + server.getAddress().getPort(),
                "owner", "repo", 7, HEAD, resilience, actualContent);
            assertThat(result.content()).isEqualTo("* @owner");
            var mapping = com.repoguard.agent.review.codeowners.CodeownersMappingCatalog.resolve(
                "[{\"tenantId\":7,\"repository\":\"owner/repo\",\"owners\":{\"@owner\":[11,12]}}]".getBytes(StandardCharsets.UTF_8),
                7, "owner/repo", new JacksonConfig().objectMapper());
            var recommendation = com.repoguard.agent.review.codeowners.CodeownersRecommendations.recommend(
                result.content(), List.of(new com.repoguard.agent.review.codeowners.CodeownersRecommendations.ChangedPath("src/A.java", "HIGH", 2)),
                mapping.owners(), java.util.Map.of(11L, "active-reviewer"));
            assertThat(recommendation.candidates()).extracting(com.repoguard.agent.review.codeowners.CodeownersRecommendations.Candidate::userId)
                .containsExactly(11L);
            assertThat(recommendation.uncoveredPaths()).isEmpty();

            assertThat(requests).containsExactly("/repos/owner/repo/pulls/7",
                "/repos/owner/repo/contents/.github/CODEOWNERS?ref=" + BASE, "/repos/owner/repo/pulls/7");
        } finally { server.stop(0); }
    }
}
