package com.repoguard.agent.scm;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class AlternativeScmProviderTest {

    private final ScmIntegrationConfigProvider configProvider = mock(ScmIntegrationConfigProvider.class);
    private final ExternalCallResilience resilience = mock(ExternalCallResilience.class);
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void giteeAdapterSupportsPullRequestsFilesCommentsAndStatuses() throws Exception {
        server = server((method, path) -> {
            if ("GET".equals(method) && path.endsWith("/pulls")) {
                return "[{\"number\":7,\"title\":\"Improve validation\",\"head\":{\"ref\":\"feature\",\"sha\":\"gitee-sha\"},"
                    + "\"user\":{\"login\":\"octocat\"},\"html_url\":\"https://gitee.example/pr/7\"}]";
            }
            if ("GET".equals(method) && path.endsWith("/pulls/7/files")) {
                return "[{\"filename\":\"src/App.java\",\"status\":\"modified\",\"additions\":2,\"deletions\":1,"
                    + "\"patch\":\"+new\\n-old\\n\"}]";
            }
            if ("GET".equals(method) && path.endsWith("/pulls/7")) {
                return "{\"head\":{\"sha\":\"gitee-sha\"}}";
            }
            if ("POST".equals(method) && path.endsWith("/comments")) {
                return "{\"id\":21,\"html_url\":\"https://gitee.example/comment/21\"}";
            }
            if ("POST".equals(method) && path.endsWith("/statuses/gitee-sha")) {
                return "{\"target_url\":\"https://gitee.example/status\"}";
            }
            return null;
        });
        when(configProvider.settings("GITEE")).thenReturn(settings("GITEE", "acme", "widgets"));
        passThroughResilience();
        GiteeScmProvider provider = new GiteeScmProvider(configProvider, RestClient.builder(), reader(), resilience, null);

        assertThat(provider.listOpenChangeRequests()).singleElement().satisfies(item -> {
            assertThat(item.number()).isEqualTo(7);
            assertThat(item.commit()).isEqualTo("gitee-sha");
        });
        assertThat(provider.fetchPullRequestDiff(task("gitee-sha")).files()).singleElement()
            .extracting(file -> file.filename(), file -> file.additions(), file -> file.deletions())
            .containsExactly("src/App.java", 2, 1);
        assertThat(provider.publishComment(task("gitee-sha"), new ScmCommentDraft(1L, null, null, "note")).remoteId())
            .isEqualTo(21L);
        assertThat(provider.publishStatus(task("gitee-sha"), new ScmStatusRequest("RepoGuard", "success", null, null)).success())
            .isTrue();
    }

    @Test
    void bitbucketAdapterMapsPullRequestsDiffstatCommentsAndBuildStatus() throws Exception {
        server = server((method, path) -> {
            if ("GET".equals(method) && path.endsWith("/pullrequests")) {
                return "{\"values\":[{\"id\":8,\"title\":\"Harden auth\",\"source\":{\"branch\":{\"name\":\"feature/auth\"},"
                    + "\"commit\":{\"hash\":\"bb-sha\"}},\"author\":{\"display_name\":\"octocat\"},"
                    + "\"links\":{\"html\":{\"href\":\"https://bitbucket.example/pr/8\"}}}]}";
            }
            if ("GET".equals(method) && path.endsWith("/pullrequests/8/diffstat")) {
                return "{\"values\":[{\"new\":{\"path\":\"src/Auth.java\"},\"old\":{\"path\":\"src/Auth.java\"},"
                    + "\"lines_added\":3,\"lines_removed\":0}]}";
            }
            if ("GET".equals(method) && path.endsWith("/pullrequests/8")) {
                return "{\"source\":{\"commit\":{\"hash\":\"bb-sha\"}}}";
            }
            if ("POST".equals(method) && path.endsWith("/comments")) {
                return "{\"id\":31,\"links\":{\"html\":{\"href\":\"https://bitbucket.example/comment/31\"}}}";
            }
            if ("POST".equals(method) && path.endsWith("/statuses/build")) {
                return "{\"url\":\"https://bitbucket.example/status\"}";
            }
            return null;
        });
        when(configProvider.settings("BITBUCKET")).thenReturn(settings("BITBUCKET", "acme", "widgets"));
        passThroughResilience();
        BitbucketScmProvider provider = new BitbucketScmProvider(configProvider, RestClient.builder(), reader(), resilience, null);

        assertThat(provider.listOpenChangeRequests()).singleElement().satisfies(item -> {
            assertThat(item.number()).isEqualTo(8);
            assertThat(item.commit()).isEqualTo("bb-sha");
        });
        assertThat(provider.fetchPullRequestDiff(task("bb-sha", 8)).files()).singleElement()
            .extracting(file -> file.filename(), file -> file.additions())
            .containsExactly("src/Auth.java", 3);
        assertThat(provider.publishComment(task("bb-sha", 8), new ScmCommentDraft(2L, "src/Auth.java", 3, "note")).remoteId())
            .isEqualTo(31L);
        assertThat(provider.publishStatus(task("bb-sha", 8), new ScmStatusRequest("RepoGuard", "failure", "failed", null)).state())
            .isEqualTo("failed");
    }

    private ExternalHttpJsonResponseReader reader() {
        return new ExternalHttpJsonResponseReader(new ObjectMapper(), new ExternalHttpResponseReader());
    }

    private void passThroughResilience() {
        doAnswer(invocation -> {
            java.util.function.Supplier<?> supplier = invocation.getArgument(1);
            return supplier.get();
        }).when(resilience).github(anyString(), any());
    }

    private ScmIntegrationSettings settings(String provider, String namespace, String repository) {
        return new ScmIntegrationSettings(provider, "CONFIGURED", baseUrl(), "token-for-test", null,
            namespace, repository, 4L);
    }

    private ReviewTask task(String sha) {
        return task(sha, 7);
    }

    private ReviewTask task(String sha, int number) {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        task.setOrganization("acme");
        task.setRepository("widgets");
        task.setPrNumber(number);
        task.setCommitSha(sha);
        return task;
    }

    private HttpServer server(ResponseFactory responseFactory) throws IOException {
        HttpServer value = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        value.createContext("/", exchange -> {
            String body = responseFactory.body(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath());
            if (body == null) {
                write(exchange, 404, "{\"message\":\"not found\"}");
                return;
            }
            exchange.getRequestBody().readAllBytes();
            write(exchange, 200, body);
        });
        value.start();
        return value;
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

    @FunctionalInterface
    private interface ResponseFactory {
        String body(String method, String path);
    }
}
