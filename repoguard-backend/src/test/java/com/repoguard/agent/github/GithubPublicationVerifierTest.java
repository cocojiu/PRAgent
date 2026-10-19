package com.repoguard.agent.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class GithubPublicationVerifierTest {
    @Test
    void verifiesPositiveIdentityUsingOnlyFixedGetEndpoints() throws Exception { scenario("valid", true); }
    @Test
    void rejectsDifferentAuthor() throws Exception { scenario("author", false); }
    @Test
    void rejectsDifferentPullRequest() throws Exception { scenario("pr", false); }
    @Test
    void rejectsMissingMarker() throws Exception { scenario("marker", false); }
    @Test
    void missingRemoteResourceNeverClearsTheUncertainOutcome() throws Exception { scenario("missing", false); }
    @Test
    void rejectsChangedTaskCommitBeforeNetwork() throws Exception {
        var integrations = mock(GithubIntegrationProvider.class);
        var verifier = new GithubPublicationVerifier(RestClient.builder(), reader(), integrations,
            mock(ExternalCallResilience.class), mock(OutboundEndpointPolicy.class), new GithubAppProperties());
        var task = task();
        assertThatThrownBy(() -> verifier.verify(task, GithubPublicationIntent.create("different", "body"), "review", 1))
            .hasMessageContaining("待核验");
        org.mockito.Mockito.verifyNoInteractions(integrations);
    }
    private void scenario(String variation, boolean succeeds) throws Exception {
        var task = task();
        String marker = GithubPublicationIntent.create(task.getCommitSha(), "fixture");
        AtomicInteger posts = new AtomicInteger();
        AtomicInteger gets = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        ObjectMapper mapper = new ObjectMapper();
        server.createContext("/", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) posts.incrementAndGet(); else gets.incrementAndGet();
            Object payload = exchange.getRequestURI().getPath().equals("/user") ? java.util.Map.of("id", 7)
                : java.util.Map.of("id", 123, "body", "marker".equals(variation) ? "none" : marker,
                    "issue_url", base + "/repos/fixture/sample/issues/" + ("pr".equals(variation) ? 10 : 9),
                    "user", java.util.Map.of("id", "author".equals(variation) ? 8 : 7),
                    "html_url", "https://example.invalid/comment/123");
            byte[] bytes = mapper.writeValueAsBytes(payload);
            exchange.sendResponseHeaders("missing".equals(variation) ? 404 : 200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var integrations = mock(GithubIntegrationProvider.class);
            when(integrations.getSettingsForRepository(anyString(), anyString())).thenReturn(
                new GithubIntegrationSettings("GITHUB", "CONFIGURED", base, "fixture", null, "fixture", "sample", null));
            var resilience = mock(ExternalCallResilience.class);
            when(resilience.github(anyString(), any())).thenAnswer(call -> ((java.util.function.Supplier<?>)call.getArgument(1)).get());
            var verifier = new GithubPublicationVerifier(RestClient.builder(), reader(), integrations, resilience,
                mock(OutboundEndpointPolicy.class), new GithubAppProperties());
            if (succeeds) assertThat(verifier.verify(task, marker, "issue_comment", 123).id()).isEqualTo(123);
            else assertThatThrownBy(() -> verifier.verify(task, marker, "issue_comment", 123)).isInstanceOf(RuntimeException.class);
            assertThat(posts).hasValue(0); assertThat(gets.get()).isBetween(1, 2);
        } finally { server.stop(0); }
    }
    private ExternalHttpJsonResponseReader reader() {
        return new ExternalHttpJsonResponseReader(new ObjectMapper(), new ExternalHttpResponseReader());
    }
    private ReviewTask task() {
        var task = new ReviewTask(); task.setId(1L); task.setOrganization("fixture"); task.setRepository("sample");
        task.setPrNumber(9); task.setCommitSha("a".repeat(40)); return task;
    }
}
