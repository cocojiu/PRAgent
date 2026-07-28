package com.repoguard.agent.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.config.GithubIntegrationProvider;
import com.repoguard.agent.config.GithubIntegrationSettings;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalCallException;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.external.ExternalHttpJsonResponseReader;
import com.repoguard.agent.external.ExternalHttpResponseReader;
import com.repoguard.agent.external.OutboundEndpointPolicy;
import com.repoguard.agent.external.OutboundEndpointType;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.worker.ReviewExecutionFailureClassifier;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

class GithubPullRequestClientImplTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExternalHttpResponseReader responseReader = new ExternalHttpResponseReader();
    private final ExternalHttpJsonResponseReader jsonResponseReader = new ExternalHttpJsonResponseReader(
        objectMapper,
        responseReader
    );
    private final GithubIntegrationProvider githubIntegrationProvider = org.mockito.Mockito.mock(GithubIntegrationProvider.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final GithubIntegrationHealthReporter healthReporter = new GithubIntegrationHealthReporter(
        githubIntegrationProvider,
        RepoGuardMetrics.forTesting(meterRegistry, new ReviewExecutionFailureClassifier())
    );
    private final GithubPullRequestClientImpl client = client(RestClient.builder(), healthReporter);

    @Test
    void constructorRejectsMissingHealthReporterMetrics() {
        assertThatThrownBy(() -> new GithubIntegrationHealthReporter(githubIntegrationProvider, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("metrics");
    }

    @Test
    void constructorRejectsMissingResilience() {
        GithubPaginator paginator = paginator(RestClient.builder());

        assertThatThrownBy(() -> new GithubPullRequestClientImpl(
            githubIntegrationProvider,
            null,
            new GithubPullRequestReader(paginator),
            new GithubPullRequestHeadReader(RestClient.builder(), jsonResponseReader),
            new GithubChangedFileReader(paginator),
            commentWriter(RestClient.builder(), healthReporter),
            healthReporter
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("resilience");
    }

    @Test
    void commentWriterValidatesBaseUrlBeforePublishingWithToken() {
        OutboundEndpointPolicy endpointPolicy = org.mockito.Mockito.mock(OutboundEndpointPolicy.class);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("rejected endpoint"))
            .when(endpointPolicy)
            .validate(OutboundEndpointType.GITHUB, "https://blocked.example");
        GithubCommentWriter writer = new GithubCommentWriter(
            RestClient.builder(),
            healthReporter,
            jsonResponseReader,
            new GithubPullRequestHeadReader(RestClient.builder(), jsonResponseReader),
            endpointPolicy
        );

        assertThatThrownBy(() -> writer.publishPullRequestComments(
            githubSettings("https://blocked.example"),
            "https://blocked.example",
            "octocat",
            "api",
            reviewTask(),
            List.of(new GithubReviewCommentDraft(10L, null, null, "summary", "pull_request")),
            passthroughResilience()
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("rejected endpoint");
    }

    @Test
    void getConfiguredRepositoryReturnsTrimmedProviderRepository() {
        when(githubIntegrationProvider.getSettings()).thenReturn(new GithubIntegrationSettings(
            "GITHUB",
            "CONFIGURED",
            "https://api.github.com",
            "ghp_test",
            null,
            " octocat ",
            " api ",
            7L
        ));

        GithubRepositoryRef repository = client.getConfiguredRepository();

        assertThat(repository.owner()).isEqualTo("octocat");
        assertThat(repository.repository()).isEqualTo("api");
    }

    @Test
    void fetchPullRequestDiffReadsSinglePageChangedFiles() throws Exception {
        try (GithubApiServer server = startGithubApiServer(30, 0)) {
            when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings(server.baseUrl()));

            GithubPullRequestDiff diff = client.fetchPullRequestDiff(reviewTask());

            assertThat(diff.files()).hasSize(30);
            assertThat(diff.headSha()).isEqualTo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
            assertThat(diff.files().get(0).filename()).isEqualTo("src/File001.java");
            assertThat(server.filesPageRequests()).containsExactly(1);
            assertThat(server.headRequests().get()).isEqualTo(2);
            var counter = meterRegistry.find("repoguard.github.api.request")
                .tag("operation", "fetch_pull_request_diff")
                .tag("result", "success")
                .tag("category", "unknown")
                .tag("status", "unknown")
                .counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }
    }

    @Test
    void fetchPullRequestDiffStopsBeforeFilesWhenTaskCommitIsNotCurrentHead() throws Exception {
        try (GithubApiServer server = startGithubApiServer(30, 0)) {
            server.setHeadSha("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
            when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings(server.baseUrl()));

            assertThatThrownBy(() -> client.fetchPullRequestDiff(reviewTask()))
                .isInstanceOf(GithubPullRequestHeadChangedException.class)
                .hasMessageContaining("expected=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .hasMessageContaining("current=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

            assertThat(server.filesPageRequests()).isEmpty();
            assertThat(server.headRequests().get()).isEqualTo(1);
        }
    }

    @Test
    void fetchPullRequestDiffRejectsHeadChangeWhileFilesAreBeingFetched() throws Exception {
        try (GithubApiServer server = startGithubApiServer(30, 0)) {
            server.changeHeadAfterNextFilesRequest("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
            when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings(server.baseUrl()));

            assertThatThrownBy(() -> client.fetchPullRequestDiff(reviewTask()))
                .isInstanceOf(GithubPullRequestHeadChangedException.class)
                .hasMessageContaining("current=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

            assertThat(server.filesPageRequests()).containsExactly(1);
            assertThat(server.headRequests().get()).isEqualTo(2);
        }
    }

    @Test
    void fetchPullRequestDiffRecordsClassifiedFailureThroughHealthReporter() throws Exception {
        try (GithubApiServer server = startGithubApiServer(30, 0)) {
            server.failNextFilesRequestWithStatus(429);
            when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings(server.baseUrl()));

            assertThatThrownBy(() -> client.fetchPullRequestDiff(reviewTask()))
                .isInstanceOf(ExternalCallException.class)
                .hasMessageContaining("github_rate_limited")
                .hasMessageContaining("status=429");

            var counter = meterRegistry.find("repoguard.github.api.request")
                .tag("operation", "fetch_pull_request_diff")
                .tag("result", "failed")
                .tag("category", "github_rate_limited")
                .tag("status", "429")
                .counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
            org.mockito.Mockito.verify(githubIntegrationProvider).markChecked(
                org.mockito.ArgumentMatchers.any(GithubIntegrationSettings.class),
                org.mockito.ArgumentMatchers.contains("github_rate_limited")
            );
        }
    }

    @Test
    void fetchPullRequestDiffReadsBeyondGithubDefaultThirtyFiles() throws Exception {
        try (GithubApiServer server = startGithubApiServer(50, 0)) {
            when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings(server.baseUrl()));

            GithubPullRequestDiff diff = client.fetchPullRequestDiff(reviewTask());

            assertThat(diff.files()).hasSize(50);
            assertThat(diff.files().get(49).filename()).isEqualTo("src/File050.java");
            assertThat(server.filesPageRequests()).containsExactly(1);
        }
    }

    @Test
    void fetchPullRequestDiffReadsMultiplePagesChangedFiles() throws Exception {
        try (GithubApiServer server = startGithubApiServer(101, 0)) {
            when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings(server.baseUrl()));

            GithubPullRequestDiff diff = client.fetchPullRequestDiff(reviewTask());

            assertThat(diff.files()).hasSize(101);
            assertThat(diff.files().get(100).filename()).isEqualTo("src/File101.java");
            assertThat(server.filesPageRequests()).containsExactly(1, 2);
        }
    }

    @Test
    void listOpenPullRequestsReadsBeyondLegacyFiftyLimit() throws Exception {
        try (GithubApiServer server = startGithubApiServer(0, 101)) {
            when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings(server.baseUrl()));

            List<GithubPullRequestSummary> pullRequests = client.listOpenPullRequests();

            assertThat(pullRequests).hasSize(101);
            assertThat(pullRequests.get(0).number()).isEqualTo(1);
            assertThat(pullRequests.get(100).number()).isEqualTo(101);
            assertThat(server.pullRequestPageRequests()).containsExactly(1, 2);
        }
    }

    @Test
    void publishPullRequestCommentsKeepsPrCommentBehavior() throws Exception {
        try (GithubApiServer server = startGithubApiServer(0, 0)) {
            when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings(server.baseUrl()));
            GithubReviewCommentDraft draft = new GithubReviewCommentDraft(
                10L,
                null,
                null,
                "PR summary",
                "pull_request"
            );

            List<GithubReviewCommentResult> results = client.publishPullRequestComments(reviewTask(), List.of(draft));

            assertThat(results).hasSize(1);
            assertThat(results.get(0).success()).isTrue();
            assertThat(results.get(0).status()).isEqualTo("published");
            assertThat(results.get(0).targetType()).isEqualTo("pull_request");
            assertThat(results.get(0).commentId()).isEqualTo(9001L);
            assertThat(server.commentPaths()).containsExactly("/repos/octocat/api/issues/7/comments");
            assertThat(server.commentBodies().get(0)).contains("PR summary");
        }
    }

    @Test
    void publishPullRequestCommentsPublishesLineCommentsAsSingleReviewBatch() throws Exception {
        try (GithubApiServer server = startGithubApiServer(0, 0)) {
            when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings(server.baseUrl()));
            server.setReviewCommentsResponse("""
                [{"id":7002,"html_url":"https://github.com/octocat/api/pull/7#discussion_r7002","path":"src/Util.java","line":30,"original_line":30,"body":"Util comment"},
                 {"id":7001,"html_url":"https://github.com/octocat/api/pull/7#discussion_r7001","path":"src/App.java","line":12,"original_line":12,"body":"App comment"}]
                """);
            List<GithubReviewCommentDraft> drafts = List.of(
                new GithubReviewCommentDraft(10L, null, null, "PR summary", "pull_request"),
                new GithubReviewCommentDraft(11L, "src/App.java", 12, "App comment", "line"),
                new GithubReviewCommentDraft(12L, "src/Util.java", 30, "Util comment", "line")
            );

            List<GithubReviewCommentResult> results = client.publishPullRequestComments(reviewTask(), drafts);

            assertThat(results).hasSize(3);
            assertThat(results.get(0).success()).isTrue();
            assertThat(results.get(0).status()).isEqualTo("published");
            assertThat(results.get(0).targetType()).isEqualTo("pull_request");
            assertThat(results.get(0).commentId()).isEqualTo(9001L);
            assertThat(results.get(1).success()).isTrue();
            assertThat(results.get(1).status()).isEqualTo("published");
            assertThat(results.get(1).targetType()).isEqualTo("line");
            assertThat(results.get(1).commentId()).isEqualTo(7001L);
            assertThat(results.get(1).url()).isEqualTo("https://github.com/octocat/api/pull/7#discussion_r7001");
            assertThat(results.get(2).commentId()).isEqualTo(7002L);
            assertThat(results.get(2).url()).isEqualTo("https://github.com/octocat/api/pull/7#discussion_r7002");
            assertThat(server.commentPaths()).containsExactly(
                "/repos/octocat/api/issues/7/comments",
                "/repos/octocat/api/pulls/7/reviews"
            );
            assertThat(server.reviewCommentListRequests())
                .containsExactly("/repos/octocat/api/pulls/7/reviews/5001/comments?per_page=100");
            assertThat(server.commentBodies().get(1))
                .contains("\"commit_id\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"")
                .contains("\"event\":\"COMMENT\"")
                .contains("\"path\":\"src/App.java\"")
                .contains("\"path\":\"src/Util.java\"")
                .contains("\"side\":\"RIGHT\"");
        }
    }

    @Test
    void publishPullRequestCommentsDowngradesAllLineDraftsWhenHeadChanged() throws Exception {
        try (GithubApiServer server = startGithubApiServer(0, 0)) {
            server.setHeadSha("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
            when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings(server.baseUrl()));
            List<GithubReviewCommentDraft> drafts = List.of(
                new GithubReviewCommentDraft(11L, "src/App.java", 12, "App comment", "line"),
                new GithubReviewCommentDraft(12L, "src/Util.java", 30, "Util comment", "line")
            );

            List<GithubReviewCommentResult> results = client.publishPullRequestComments(reviewTask(), drafts);

            assertThat(results).allSatisfy(result -> {
                assertThat(result.success()).isTrue();
                assertThat(result.status()).isEqualTo("downgraded_to_pr_comment");
                assertThat(result.targetType()).isEqualTo("pull_request");
                assertThat(result.commentId()).isEqualTo(9001L);
            });
            assertThat(server.commentPaths()).containsExactly("/repos/octocat/api/issues/7/comments");
            assertThat(server.commentBodies().get(0))
                .contains("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .contains("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
                .contains("src/App.java:12")
                .contains("src/Util.java:30");
            assertThat(server.headRequests().get()).isEqualTo(1);
        }
    }

    @Test
    void publishPullRequestCommentsStopsInlineFallbackWhenHeadChangesAfterBatchValidation() throws Exception {
        try (GithubApiServer server = startGithubApiServer(0, 0)) {
            server.failNextReviewWithStatus(422, "{\"message\":\"Validation Failed\"}");
            server.changeHeadAfterNextReviewRequest("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
            when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings(server.baseUrl()));
            List<GithubReviewCommentDraft> drafts = List.of(
                new GithubReviewCommentDraft(11L, "src/App.java", 12, "App comment", "line"),
                new GithubReviewCommentDraft(12L, "src/Util.java", 30, "Util comment", "line")
            );

            List<GithubReviewCommentResult> results = client.publishPullRequestComments(reviewTask(), drafts);

            assertThat(results).allSatisfy(result -> {
                assertThat(result.success()).isTrue();
                assertThat(result.status()).isEqualTo("downgraded_to_pr_comment");
                assertThat(result.targetType()).isEqualTo("pull_request");
            });
            assertThat(server.commentPaths()).containsExactly(
                "/repos/octocat/api/pulls/7/reviews",
                "/repos/octocat/api/issues/7/comments"
            );
            assertThat(server.commentBodies().get(1))
                .contains("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .contains("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        }
    }

    @Test
    void publishPullRequestCommentsFallsBackToPerCommentPublishingWhenReviewRejected() throws Exception {
        try (GithubApiServer server = startGithubApiServer(0, 0)) {
            server.failNextReviewWithStatus(422, "{\"message\":\"Validation Failed\"}");
            server.failNextLineCommentAsUnresolvable();
            when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings(server.baseUrl()));
            List<GithubReviewCommentDraft> drafts = List.of(
                new GithubReviewCommentDraft(11L, "src/App.java", 12, "Line comment", "line"),
                new GithubReviewCommentDraft(12L, "src/Util.java", 30, "Other comment", "line")
            );

            List<GithubReviewCommentResult> results = client.publishPullRequestComments(reviewTask(), drafts);

            assertThat(results).hasSize(2);
            assertThat(results.get(0).success()).isTrue();
            assertThat(results.get(0).status()).isEqualTo("downgraded_to_pr_comment");
            assertThat(results.get(0).targetType()).isEqualTo("pull_request");
            assertThat(results.get(1).success()).isTrue();
            assertThat(results.get(1).status()).isEqualTo("published");
            assertThat(results.get(1).targetType()).isEqualTo("line");
            assertThat(results.get(1).commentId()).isEqualTo(9002L);
            assertThat(server.commentPaths()).containsExactly(
                "/repos/octocat/api/pulls/7/reviews",
                "/repos/octocat/api/pulls/7/comments",
                "/repos/octocat/api/issues/7/comments",
                "/repos/octocat/api/pulls/7/comments"
            );
        }
    }

    @Test
    void publishPullRequestCommentsKeepsSuccessWhenReviewCommentListingFails() throws Exception {
        try (GithubApiServer server = startGithubApiServer(0, 0)) {
            server.failNextReviewCommentsWithStatus(500);
            when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings(server.baseUrl()));
            GithubReviewCommentDraft draft = new GithubReviewCommentDraft(
                11L,
                "src/App.java",
                12,
                "Line comment",
                "line"
            );

            List<GithubReviewCommentResult> results = client.publishPullRequestComments(reviewTask(), List.of(draft));

            assertThat(results).hasSize(1);
            assertThat(results.get(0).success()).isTrue();
            assertThat(results.get(0).status()).isEqualTo("published");
            assertThat(results.get(0).commentId()).isNull();
            assertThat(results.get(0).url()).isEqualTo("https://github.com/octocat/api/pull/7#pullrequestreview-5001");
            assertThat(server.commentPaths()).containsExactly("/repos/octocat/api/pulls/7/reviews");
        }
    }

    @Test
    void publishPullRequestCommentsFailsAllLineCommentsWhenReviewRejectedWithoutValidationError() throws Exception {
        try (GithubApiServer server = startGithubApiServer(0, 0)) {
            server.failNextReviewWithStatus(403, "{\"message\":\"You have exceeded a secondary rate limit\"}");
            when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings(server.baseUrl()));
            List<GithubReviewCommentDraft> drafts = List.of(
                new GithubReviewCommentDraft(11L, "src/App.java", 12, "Line comment", "line"),
                new GithubReviewCommentDraft(12L, "src/Util.java", 30, "Other comment", "line")
            );

            List<GithubReviewCommentResult> results = client.publishPullRequestComments(reviewTask(), drafts);

            assertThat(results).hasSize(2);
            assertThat(results.get(0).success()).isFalse();
            assertThat(results.get(0).status()).isEqualTo("failed");
            assertThat(results.get(0).message()).contains("github_permission_denied").contains("status=403");
            assertThat(results.get(1).success()).isFalse();
            assertThat(results.get(1).status()).isEqualTo("failed");
            assertThat(server.commentPaths()).containsExactly("/repos/octocat/api/pulls/7/reviews");
        }
    }

    @Test
    void publishPullRequestCommentsKeepsSharedReaderFailureDetail() throws Exception {
        try (GithubApiServer server = startGithubApiServer(0, 0)) {
            server.failNextPrCommentWithStatus(
                429,
                "60",
                "{\"message\":\"rate limited\",\"token\":\"raw-token-value\"}"
            );
            when(githubIntegrationProvider.getSettings()).thenReturn(githubSettings(server.baseUrl()));
            GithubReviewCommentDraft draft = new GithubReviewCommentDraft(
                12L,
                null,
                null,
                "PR summary",
                "pull_request"
            );

            List<GithubReviewCommentResult> results = client.publishPullRequestComments(reviewTask(), List.of(draft));

            assertThat(results).hasSize(1);
            assertThat(results.get(0).success()).isFalse();
            assertThat(results.get(0).status()).isEqualTo("failed");
            assertThat(results.get(0).message())
                .contains("github_rate_limited")
                .contains("status=429")
                .contains("retryAfter=60")
                .doesNotContain("raw-token-value");
            assertThat(server.commentPaths()).containsExactly("/repos/octocat/api/issues/7/comments");
        }
    }

    private GithubIntegrationSettings githubSettings(String baseUrl) {
        return new GithubIntegrationSettings(
            "GITHUB",
            "CONFIGURED",
            baseUrl,
            "ghp_test",
            null,
            "octocat",
            "api",
            7L
        );
    }

    private GithubPullRequestClientImpl client(
        RestClient.Builder restClientBuilder,
        GithubIntegrationHealthReporter healthReporter
    ) {
        GithubPaginator paginator = paginator(restClientBuilder);
        GithubPullRequestHeadReader headReader = new GithubPullRequestHeadReader(
            restClientBuilder,
            jsonResponseReader
        );
        return new GithubPullRequestClientImpl(
            githubIntegrationProvider,
            passthroughResilience(),
            new GithubPullRequestReader(paginator),
            headReader,
            new GithubChangedFileReader(paginator),
            commentWriter(restClientBuilder, healthReporter),
            healthReporter
        );
    }

    private GithubPaginator paginator(RestClient.Builder restClientBuilder) {
        return new GithubPaginator(restClientBuilder, jsonResponseReader, 100);
    }

    private GithubCommentWriter commentWriter(
        RestClient.Builder restClientBuilder,
        GithubIntegrationHealthReporter healthReporter
    ) {
        return new GithubCommentWriter(restClientBuilder, healthReporter, jsonResponseReader);
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

    private ReviewTask reviewTask() {
        ReviewTask task = new ReviewTask();
        task.setOrganization("octocat");
        task.setRepository("api");
        task.setPrNumber(7);
        task.setCommitSha("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        return task;
    }

    private GithubApiServer startGithubApiServer(int changedFileCount, int pullRequestCount) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<Integer> filesPageRequests = new ArrayList<>();
        List<Integer> pullRequestPageRequests = new ArrayList<>();
        List<String> commentPaths = new ArrayList<>();
        List<String> commentBodies = new ArrayList<>();
        List<String> reviewCommentListRequests = new ArrayList<>();
        AtomicInteger lineCommentFailures = new AtomicInteger();
        AtomicInteger filesFailureStatus = new AtomicInteger();
        AtomicInteger prCommentFailureStatus = new AtomicInteger();
        AtomicReference<String> prCommentFailureRetryAfter = new AtomicReference<>();
        AtomicReference<String> prCommentFailureBody = new AtomicReference<>();
        AtomicInteger reviewFailureStatus = new AtomicInteger();
        AtomicReference<String> reviewFailureBody = new AtomicReference<>();
        AtomicInteger reviewCommentsFailureStatus = new AtomicInteger();
        AtomicReference<String> reviewCommentsBody = new AtomicReference<>("[]");
        AtomicReference<String> headSha = new AtomicReference<>("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        AtomicReference<String> headAfterFilesSha = new AtomicReference<>();
        AtomicReference<String> headAfterReviewSha = new AtomicReference<>();
        AtomicInteger headRequests = new AtomicInteger();
        server.createContext("/", exchange -> {
            URI uri = exchange.getRequestURI();
            String body;
            if ("GET".equals(exchange.getRequestMethod())
                && "/repos/octocat/api/pulls/7".equals(uri.getPath())) {
                headRequests.incrementAndGet();
                body = "{\"head\":{\"sha\":\"" + headSha.get() + "\"}}";
            } else if ("/repos/octocat/api/pulls/7/files".equals(uri.getPath())) {
                int failureStatus = filesFailureStatus.getAndSet(0);
                if (failureStatus > 0) {
                    body = "{\"message\":\"API rate limit exceeded\"}";
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(failureStatus, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                    return;
                }
                int page = queryInt(uri, "page", 1);
                int perPage = queryInt(uri, "per_page", 30);
                filesPageRequests.add(page);
                body = changedFilesJson(changedFileCount, page, perPage);
                String replacementHeadSha = headAfterFilesSha.getAndSet(null);
                if (replacementHeadSha != null) {
                    headSha.set(replacementHeadSha);
                }
            } else if ("/repos/octocat/api/pulls".equals(uri.getPath())) {
                int page = queryInt(uri, "page", 1);
                int perPage = queryInt(uri, "per_page", 30);
                pullRequestPageRequests.add(page);
                body = pullRequestsJson(pullRequestCount, page, perPage);
            } else if ("POST".equals(exchange.getRequestMethod())
                && "/repos/octocat/api/pulls/7/reviews".equals(uri.getPath())) {
                commentPaths.add(uri.getPath());
                commentBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String replacementHeadSha = headAfterReviewSha.getAndSet(null);
                if (replacementHeadSha != null) {
                    headSha.set(replacementHeadSha);
                }
                int failureStatus = reviewFailureStatus.getAndSet(0);
                if (failureStatus > 0) {
                    body = reviewFailureBody.get();
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(failureStatus, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                    return;
                }
                body = "{\"id\":5001,\"html_url\":\"https://github.com/octocat/api/pull/7#pullrequestreview-5001\"}";
            } else if ("GET".equals(exchange.getRequestMethod())
                && "/repos/octocat/api/pulls/7/reviews/5001/comments".equals(uri.getPath())) {
                reviewCommentListRequests.add(
                    uri.getPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                );
                int failureStatus = reviewCommentsFailureStatus.getAndSet(0);
                if (failureStatus > 0) {
                    body = "{\"message\":\"server error\"}";
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(failureStatus, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                    return;
                }
                body = reviewCommentsBody.get();
            } else if ("POST".equals(exchange.getRequestMethod())
                && "/repos/octocat/api/pulls/7/comments".equals(uri.getPath())) {
                commentPaths.add(uri.getPath());
                commentBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                if (lineCommentFailures.getAndDecrement() > 0) {
                    body = "{\"message\":\"Validation Failed: could not be resolved to a diff line\"}";
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(422, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                    return;
                }
                body = "{\"id\":9002,\"html_url\":\"https://github.com/octocat/api/pull/7#discussion_r9002\"}";
            } else if ("POST".equals(exchange.getRequestMethod())
                && "/repos/octocat/api/issues/7/comments".equals(uri.getPath())) {
                commentPaths.add(uri.getPath());
                commentBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                int failureStatus = prCommentFailureStatus.getAndSet(0);
                if (failureStatus > 0) {
                    body = prCommentFailureBody.get();
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    String retryAfter = prCommentFailureRetryAfter.get();
                    if (retryAfter != null) {
                        exchange.getResponseHeaders().set(HttpHeaders.RETRY_AFTER, retryAfter);
                    }
                    exchange.sendResponseHeaders(failureStatus, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                    return;
                }
                body = "{\"id\":9001,\"html_url\":\"https://github.com/octocat/api/pull/7#issuecomment-9001\"}";
            } else {
                exchange.sendResponseHeaders(404, 0);
                exchange.close();
                return;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new GithubApiServer(
            server,
            "http://127.0.0.1:" + server.getAddress().getPort(),
            filesPageRequests,
            pullRequestPageRequests,
            commentPaths,
            commentBodies,
            reviewCommentListRequests,
            lineCommentFailures,
            filesFailureStatus,
            prCommentFailureStatus,
            prCommentFailureRetryAfter,
            prCommentFailureBody,
            reviewFailureStatus,
            reviewFailureBody,
            reviewCommentsFailureStatus,
            reviewCommentsBody,
            headSha,
            headAfterFilesSha,
            headAfterReviewSha,
            headRequests
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

    private String pullRequestsJson(int total, int page, int perPage) {
        int start = (page - 1) * perPage + 1;
        int end = Math.min(total, page * perPage);
        List<String> items = new ArrayList<>();
        for (int i = start; i <= end; i++) {
            items.add("""
                {"number":%d,"title":"PR %03d","head":{"ref":"feature-%03d","sha":"abc%03d"},"user":{"login":"alice"},"html_url":"https://github.com/octocat/api/pull/%d","updated_at":"2026-06-21T00:00:00Z"}
                """.formatted(i, i, i, i, i).trim());
        }
        return "[" + String.join(",", items) + "]";
    }

    private record GithubApiServer(
        HttpServer server,
        String baseUrl,
        List<Integer> filesPageRequests,
        List<Integer> pullRequestPageRequests,
        List<String> commentPaths,
        List<String> commentBodies,
        List<String> reviewCommentListRequests,
        AtomicInteger lineCommentFailures,
        AtomicInteger filesFailureStatus,
        AtomicInteger prCommentFailureStatus,
        AtomicReference<String> prCommentFailureRetryAfter,
        AtomicReference<String> prCommentFailureBody,
        AtomicInteger reviewFailureStatus,
        AtomicReference<String> reviewFailureBody,
        AtomicInteger reviewCommentsFailureStatus,
        AtomicReference<String> reviewCommentsBody,
        AtomicReference<String> headSha,
        AtomicReference<String> headAfterFilesSha,
        AtomicReference<String> headAfterReviewSha,
        AtomicInteger headRequests
    ) implements AutoCloseable {

        @Override
        public void close() {
            server.stop(0);
        }

        void failNextLineCommentAsUnresolvable() {
            lineCommentFailures.set(1);
        }

        void failNextFilesRequestWithStatus(int status) {
            filesFailureStatus.set(status);
        }

        void failNextPrCommentWithStatus(int status, String retryAfter, String body) {
            prCommentFailureStatus.set(status);
            prCommentFailureRetryAfter.set(retryAfter);
            prCommentFailureBody.set(body);
        }

        void failNextReviewWithStatus(int status, String body) {
            reviewFailureStatus.set(status);
            reviewFailureBody.set(body);
        }

        void failNextReviewCommentsWithStatus(int status) {
            reviewCommentsFailureStatus.set(status);
        }

        void setReviewCommentsResponse(String body) {
            reviewCommentsBody.set(body);
        }

        void setHeadSha(String sha) {
            headSha.set(sha);
        }

        void changeHeadAfterNextFilesRequest(String sha) {
            headAfterFilesSha.set(sha);
        }

        void changeHeadAfterNextReviewRequest(String sha) {
            headAfterReviewSha.set(sha);
        }
    }
}
