package com.repoguard.agent.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.external.ExternalHttpJsonResponseReader;
import com.repoguard.agent.external.ExternalHttpResponseReader;
import com.repoguard.agent.review.PullRequestDiff;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class GitlabScmProviderTest {

    private final ScmIntegrationConfigProvider configProvider = mock(ScmIntegrationConfigProvider.class);
    private final ExternalCallResilience resilience = mock(ExternalCallResilience.class);
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> requestBodies = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private String responseMode = "normal";

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void readsMergeRequestsDiffHeadAndPublishesNoteAndStatus() throws Exception {
        startServer();
        ScmIntegrationSettings settings = settings();
        when(configProvider.settings("GITLAB")).thenReturn(settings);
        passThroughResilience();
        GitlabScmProvider provider = provider();

        List<ScmChangeRequestSummary> mergeRequests = provider.listOpenChangeRequests();
        ReviewTask task = task();
        PullRequestDiff diff = provider.fetchPullRequestDiff(task);
        ScmCommentResult comment = provider.publishComment(task, new ScmCommentDraft(19L, "src/App.java", 4, "Please validate input"));
        ScmStatusResult status = provider.publishStatus(task, new ScmStatusRequest("RepoGuard", "success", "passed", "https://gitlab.example/status"));

        assertThat(mergeRequests).singleElement().satisfies(item -> {
            assertThat(item.number()).isEqualTo(7);
            assertThat(item.title()).isEqualTo("Improve validation");
            assertThat(item.commit()).isEqualTo("head-sha");
            assertThat(item.author()).isEqualTo("octocat");
        });
        assertThat(diff.owner()).isEqualTo("acme");
        assertThat(diff.repository()).isEqualTo("widgets");
        assertThat(diff.headSha()).isEqualTo("head-sha");
        assertThat(diff.files()).singleElement().satisfies(file -> {
            assertThat(file.filename()).isEqualTo("src/App.java");
            assertThat(file.status()).isEqualTo("modified");
            assertThat(file.additions()).isEqualTo(1);
            assertThat(file.deletions()).isEqualTo(1);
        });
        assertThat(comment).extracting(ScmCommentResult::success, ScmCommentResult::remoteId)
            .containsExactly(true, 12L);
        assertThat(status).extracting(ScmStatusResult::success, ScmStatusResult::state)
            .containsExactly(true, "success");
        assertThat(requests).anyMatch(path -> path.contains("/merge_requests"));
        assertThat(requests).anyMatch(path -> path.endsWith("/notes"));
        assertThat(requests).anyMatch(path -> path.endsWith("/statuses/head-sha"));
        assertThat(requestBodies).anyMatch(body -> body.contains("Please validate input"));
        assertThat(requestBodies).anyMatch(body -> body.contains("\"state\":\"success\""));
    }

    @Test
    void validatesRequiredConfigurationAndStatusState() {
        when(configProvider.settings("GITLAB"))
            .thenReturn(new ScmIntegrationSettings("GITLAB", "NOT_CONFIGURED", null, null, null, null, null, null));
        GitlabScmProvider provider = provider();

        assertThatThrownBy(provider::listOpenChangeRequests)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("token");
        assertThatThrownBy(() -> provider.publishStatus(task(), new ScmStatusRequest("name", "unknown", null, null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("token");
    }

    @Test
    void rejectsUnsupportedStatusAfterConfigurationIsLoaded() {
        when(configProvider.settings("GITLAB")).thenReturn(new ScmIntegrationSettings("GITLAB", "CONFIGURED",
            "https://gitlab.com", "token-for-test", null, "acme", "widgets", 3L));
        GitlabScmProvider provider = provider();

        assertThatThrownBy(() -> provider.publishStatus(task(), new ScmStatusRequest("name", "unknown", null, null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported GitLab commit status");
    }

    @Test
    void exposesProviderMetadataAndAppliesDefaultStatusFields() throws Exception {
        startServer();
        when(configProvider.settings("GITLAB")).thenReturn(settings());
        passThroughResilience();
        GitlabScmProvider provider = provider();

        assertThat(provider.providerKey()).isEqualTo("GITLAB");
        assertThat(provider.configuredRepository()).isEqualTo(new ScmRepositoryRef("acme", "widgets"));
        ScmStatusResult status = provider.publishStatus(task(), null);

        assertThat(status.state()).isEqualTo("pending");
        assertThat(requestBodies).anyMatch(body -> body.contains("RepoGuard PR Review"));
        assertThatThrownBy(() -> provider.fetchPullRequestDiff(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Review task is required");
    }

    @Test
    void handlesEmptyAndMalformedProviderResponsesAndValidationEdges() throws Exception {
        startServer();
        when(configProvider.settings("GITLAB")).thenReturn(settings());
        passThroughResilience();
        GitlabScmProvider provider = provider();

        responseMode = "empty-list";
        assertThat(provider.listOpenChangeRequests()).isEmpty();

        responseMode = "edge-diff";
        PullRequestDiff diff = provider.fetchPullRequestDiff(task());
        assertThat(diff.files()).extracting(file -> file.status())
            .containsExactly("removed", "added", "renamed");

        responseMode = "large-diff";
        assertThat(provider.fetchPullRequestDiff(task()).truncated()).isTrue();

        responseMode = "missing-head";
        assertThatThrownBy(() -> provider.fetchPullRequestHeadSha(task()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("head SHA");
        assertThatThrownBy(() -> provider.publishComment(task(), new ScmCommentDraft(1L, null, null, " ")))
            .isInstanceOf(IllegalArgumentException.class);

        assertThat(provider.publishStatus(task(), new ScmStatusRequest("name", "failure", null, null)).state())
            .isEqualTo("failed");
        assertThat(provider.publishStatus(task(), new ScmStatusRequest("name", "cancelled", null, null)).state())
            .isEqualTo("canceled");
    }

    @Test
    void publicConstructorAndMissingRequestNumberRemainValidated() {
        ScmIntegrationSettings settings = new ScmIntegrationSettings(
            "GITLAB", "CONFIGURED", "https://gitlab.com", "token-for-test", null, null, null, 3L
        );
        when(configProvider.settings("GITLAB")).thenReturn(settings);
        GitlabScmProvider provider = new GitlabScmProvider(
            configProvider, RestClient.builder(), new ExternalHttpJsonResponseReader(
                new ObjectMapper(), new ExternalHttpResponseReader()
            ), resilience, null
        );

        assertThat(provider.configuredRepository()).isNull();
        ReviewTask invalidTask = new ReviewTask();
        invalidTask.setOrganization("acme");
        invalidTask.setRepository("widgets");
        assertThatThrownBy(() -> provider.fetchPullRequestHeadSha(invalidTask))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("number is required");
    }

    private GitlabScmProvider provider() {
        ExternalHttpJsonResponseReader reader = new ExternalHttpJsonResponseReader(
            new ObjectMapper(), new ExternalHttpResponseReader()
        );
        return new GitlabScmProvider(
            configProvider,
            RestClient.builder(),
            reader,
            resilience,
            null,
            true
        );
    }

    private void passThroughResilience() {
        doAnswer(invocation -> {
            java.util.function.Supplier<?> supplier = invocation.getArgument(1);
            return supplier.get();
        }).when(resilience).github(anyString(), any());
    }

    private ScmIntegrationSettings settings() {
        return new ScmIntegrationSettings("GITLAB", "CONFIGURED", baseUrl(), "token-for-test",
            null, "acme", "widgets", 3L);
    }

    private ReviewTask task() {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        task.setOrganization("acme");
        task.setRepository("widgets");
        task.setPrNumber(7);
        task.setCommitSha("head-sha");
        return task;
    }

    private void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath();
        String request = exchange.getRequestMethod() + " " + path;
        requests.add(request);
        requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String body;
        if ("empty-list".equals(responseMode) && "GET".equals(exchange.getRequestMethod())
            && path.endsWith("/merge_requests")) {
            body = "{}";
        } else if ("edge-diff".equals(responseMode) && "GET".equals(exchange.getRequestMethod())
            && path.endsWith("/changes")) {
            body = "{\"changes\":["
                + "{\"new_path\":\"src/Removed.java\",\"old_path\":\"src/Removed.java\","
                + "\"deleted_file\":true,\"diff\":\"-old\\n\"},"
                + "{\"new_path\":\"src/Added.java\",\"new_file\":true,\"diff\":\"+new\\n\"},"
                + "{\"new_path\":\"src/Renamed.java\",\"old_path\":\"src/Old.java\","
                + "\"renamed_file\":true,\"diff\":\"@@\\n\"},"
                + "{\"diff\":null}, {\"new_path\":\"\",\"diff\":\"+ignored\\n\"}]}";
        } else if ("large-diff".equals(responseMode) && "GET".equals(exchange.getRequestMethod())
            && path.endsWith("/changes")) {
            body = "{\"changes\":[{\"new_path\":\"src/Large.java\",\"diff\":\""
                + "+" + "x".repeat(512 * 1024) + "\"}]}";
        } else if ("missing-head".equals(responseMode) && "GET".equals(exchange.getRequestMethod())
            && path.endsWith("/merge_requests/7")) {
            body = "{}";
        } else if ("GET".equals(exchange.getRequestMethod()) && path.endsWith("/merge_requests")) {
            body = "[{\"iid\":7,\"title\":\"Improve validation\",\"source_branch\":\"feature/validation\"," 
                + "\"sha\":\"head-sha\",\"author\":{\"username\":\"octocat\"},"
                + "\"web_url\":\"https://gitlab.example/mr/7\",\"updated_at\":\"2026-09-01T10:00:00Z\"}]";
        } else if ("GET".equals(exchange.getRequestMethod()) && path.endsWith("/changes")) {
            body = "{\"changes\":[{\"new_path\":\"src/App.java\",\"old_path\":\"src/App.java\","
                + "\"diff\":\"@@ -1 +1 @@\\n+new value\\n-old value\\n\"}]}";
        } else if ("GET".equals(exchange.getRequestMethod()) && path.endsWith("/merge_requests/7")) {
            body = "{\"sha\":\"head-sha\",\"diff_refs\":{\"head_sha\":\"head-sha\"}}";
        } else if ("POST".equals(exchange.getRequestMethod()) && path.endsWith("/notes")) {
            body = "{\"id\":12,\"web_url\":\"https://gitlab.example/note/12\"}";
        } else if ("POST".equals(exchange.getRequestMethod()) && path.endsWith("/statuses/head-sha")) {
            body = "{\"target_url\":\"https://gitlab.example/status\"}";
        } else {
            write(exchange, 404, "{\"message\":\"not found\"}");
            return;
        }
        write(exchange, 200, body);
    }

    private void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
