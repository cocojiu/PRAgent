package com.repoguard.agent.github.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.external.ExternalHttpJsonResponseReader;
import com.repoguard.agent.external.ExternalHttpResponseReader;
import com.repoguard.agent.external.OutboundEndpointPolicy;
import com.repoguard.agent.github.GithubIntegrationProvider;
import com.repoguard.agent.github.GithubIntegrationSettings;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class GithubFeedbackVerifierTest {
    @Test
    void verifiesWritePermissionActorCommentAndLiveHeadUsingOnlyGet() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> permission = new AtomicReference<>("write");
        AtomicReference<String> body = new AtomicReference<>("/repoguard false-positive reason");
        AtomicReference<String> head = new AtomicReference<>("a".repeat(40));
        ObjectMapper mapper = new ObjectMapper();
        server.createContext("/", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            String path = exchange.getRequestURI().getPath();
            String response;
            if (path.endsWith("/permission")) response = "{\"permission\":\"" + permission.get() + "\",\"user\":{\"id\":10}}";
            else if (path.endsWith("/comments/20")) response = "{\"id\":20,\"in_reply_to_id\":30,\"user\":{\"id\":10,\"type\":\"User\"},\"commit_id\":\""
                + "a".repeat(40) + "\",\"body\":" + mapper.writeValueAsString(body.get()) + "}";
            else response = "{\"state\":\"open\",\"head\":{\"sha\":\"" + head.get() + "\"}}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            var provider = mock(GithubIntegrationProvider.class);
            when(provider.getSettingsForRepository("org", "repo")).thenReturn(new GithubIntegrationSettings(
                "GITHUB", "CONFIGURED", "http://127.0.0.1:" + server.getAddress().getPort(), "test", null, "org", "repo", 1L));
            var resilience = mock(ExternalCallResilience.class);
            when(resilience.github(anyString(), any())).thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(1).get());
            var policy = mock(OutboundEndpointPolicy.class);
            var verifier = new GithubFeedbackVerifier(provider, RestClient.builder(),
                new ExternalHttpJsonResponseReader(mapper, new ExternalHttpResponseReader()), resilience, policy);
            var command = GithubFeedbackCommand.parse(GithubFeedbackServiceTest.payload());
            assertThat(verifier.rejection(command)).isNull();
            permission.set("read");
            assertThat(verifier.rejection(command)).isEqualTo("ACTOR_PERMISSION_DENIED");
            permission.set("write"); body.set("edited");
            assertThat(verifier.rejection(command)).isEqualTo("REMOTE_COMMENT_CHANGED");
            body.set("/repoguard false-positive reason"); head.set("b".repeat(40));
            assertThat(verifier.rejection(command)).isEqualTo("HEAD_CHANGED_OR_CLOSED");
            when(provider.getSettingsForRepository("org", "repo")).thenReturn(GithubIntegrationSettings.empty());
            assertThatThrownBy(() -> verifier.rejection(command)).isInstanceOf(IllegalStateException.class);
            verify(policy, org.mockito.Mockito.atLeastOnce()).validate(any(), anyString());
        } finally { server.stop(0); }
    }
}
