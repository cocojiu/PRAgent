package com.repoguard.agent.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.config.JacksonConfig;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.external.ExternalHttpJsonResponseReader;
import com.repoguard.agent.external.ExternalHttpResponseReader;
import com.repoguard.agent.external.OutboundEndpointPolicy;
import com.repoguard.agent.review.RepositoryPolicyEvaluationService;
import com.repoguard.agent.review.RepositoryPolicyRuntime;
import com.repoguard.agent.review.RepositorySuppressionService;
import com.repoguard.agent.review.ReviewPolicyProvider;
import com.repoguard.agent.review.ReviewPolicySettings;
import com.repoguard.agent.review.ReviewResult;
import com.repoguard.agent.review.ReviewRuleProvider;
import com.repoguard.agent.review.ReviewRuleRegistry;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;
import com.sun.net.httpserver.HttpServer;

class GithubRepositoryPolicyAdapterTest {

    @Test
    void readerLoadsBaseAndHeadPoliciesAndSendsRepositoryMetadataRequest() throws Exception {
        AtomicReference<String> target = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            target.set(exchange.getRequestURI().toASCIIString());
            byte[] body = "{\"default_branch\":\"main\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            GithubIntegrationProvider provider = mock(GithubIntegrationProvider.class);
            GithubChangedFileContentReader contentReader = mock(GithubChangedFileContentReader.class);
            ExternalCallResilience resilience = passthroughResilience();
            OutboundEndpointPolicy endpointPolicy = mock(OutboundEndpointPolicy.class);
            when(endpointPolicy.validate(any(), anyString())).thenAnswer(invocation ->
                URI.create(invocation.getArgument(1)));
            GithubIntegrationSettings settings = new GithubIntegrationSettings(
                "GITHUB", "CONFIGURED", "http://127.0.0.1:" + server.getAddress().getPort(),
                "token", null, "octocat", "repo", 1L
            );
            when(provider.getSettingsForRepository("octocat", "repo")).thenReturn(settings);
            when(contentReader.fetch(any(), anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn("schemaVersion: 1\n", "schemaVersion: 1\ninclude: [src/**]\n");

            GithubRepositoryPolicyReader reader = new GithubRepositoryPolicyReader(
                provider,
                contentReader,
                resilience,
                jsonReader(),
                endpointPolicy,
                RestClient.builder()
            );

            GithubRepositoryPolicyReader.PolicySource source = reader.readForPreview("octocat", "repo", "head-1");

            assertThat(source.baseRef()).isEqualTo("main");
            assertThat(source.hasBase()).isTrue();
            assertThat(source.hasHead()).isTrue();
            assertThat(target.get()).isEqualTo("/repos/octocat/repo");
            verify(contentReader).fetch(settings, settings.baseUrl(), "octocat", "repo", "main", ".repoguard.yml", resilience);
            verify(contentReader).fetch(settings, settings.baseUrl(), "octocat", "repo", "head-1", ".repoguard.yml", resilience);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void readerFailsClosedForMissingIntegrationAndUnsafeDefaultBranch() throws Exception {
        GithubIntegrationProvider provider = mock(GithubIntegrationProvider.class);
        GithubChangedFileContentReader contentReader = mock(GithubChangedFileContentReader.class);
        when(provider.getSettingsForRepository(anyString(), anyString())).thenReturn(GithubIntegrationSettings.empty());
        GithubRepositoryPolicyReader reader = new GithubRepositoryPolicyReader(
            provider, contentReader, passthroughResilience(), jsonReader()
        );
        assertThat(reader.readForPreview("octocat", "repo", "head").error())
            .isEqualTo("github_integration_not_configured");
        assertThat(reader.readForTask(null).error()).isEqualTo("missing_repository");
        assertThatThrownBy(() -> reader.readForPreview("", "repo", "head"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("organization is required");
    }

    @Test
    void runtimeCachesEvaluationBetweenLlmAndFindingsAndDegradesInvalidPolicy() {
        ReviewPolicyProvider policyProvider = mock(ReviewPolicyProvider.class);
        ReviewRuleProvider ruleProvider = mock(ReviewRuleProvider.class);
        ReviewRuleRegistry registry = registry("RG-AUTH-001");
        GithubRepositoryPolicyReader reader = mock(GithubRepositoryPolicyReader.class);
        RepositoryPolicyEvaluationService evaluationService = new RepositoryPolicyEvaluationService(
            new com.repoguard.agent.review.ServerRiskAggregator()
        );
        ReviewPolicySettings settings = ReviewPolicySettings.empty();
        when(policyProvider.getSettings()).thenReturn(settings);
        when(ruleProvider.getRulesById()).thenReturn(Map.of());
        when(reader.readForTask(any())).thenReturn(new GithubRepositoryPolicyReader.PolicySource(
            "schemaVersion: 1\n", "schemaVersion: 1\nproviderUrl: https://blocked.invalid\n", "main", null
        ));
        when(reader.readForPreview(anyString(), anyString(), anyString())).thenReturn(
            GithubRepositoryPolicyReader.PolicySource.empty("source_warning"));
        @SuppressWarnings("unchecked")
        ObjectProvider<RepositorySuppressionService> suppressionProvider = mock(ObjectProvider.class);
        when(suppressionProvider.getIfAvailable()).thenReturn(null);
        RepositoryPolicyRuntime runtime = new GithubRepositoryPolicyRuntime(
            policyProvider, ruleProvider, registry, reader, evaluationService, suppressionProvider
        );
        ReviewTask task = new ReviewTask();
        task.setId(77L);
        task.setOrganization("octocat");
        task.setRepository("repo");
        task.setCommitSha("abc");

        ReviewPolicySettings effective = runtime.applyLlmSettings(task, settings);
        assertThat(effective).isSameAs(settings);
        ReviewResult result = ReviewResult.completed("INFO", List.of());
        assertThat(runtime.applyFindings(task, result)).isSameAs(result);
        verify(reader).readForTask(task);
        assertThat(runtime.applyFindings(task, null)).isNull();
        assertThat(runtime.preview("octocat", "repo", "head").warnings()).contains("source_warning");
    }

    @Test
    void runtimeUsesFallbackWhenPolicyReaderThrows() {
        ReviewPolicyProvider policyProvider = mock(ReviewPolicyProvider.class);
        ReviewRuleProvider ruleProvider = mock(ReviewRuleProvider.class);
        ReviewRuleRegistry registry = registry("RG-AUTH-001");
        GithubRepositoryPolicyReader reader = mock(GithubRepositoryPolicyReader.class);
        doThrow(new IllegalStateException("network")).when(reader).readForPreview("octocat", "repo", "head");
        when(policyProvider.getSettings()).thenReturn(ReviewPolicySettings.empty());
        when(ruleProvider.getRulesById()).thenReturn(Map.of());
        GithubRepositoryPolicyRuntime runtime = new GithubRepositoryPolicyRuntime(
            policyProvider, ruleProvider, registry, reader,
            new RepositoryPolicyEvaluationService(new com.repoguard.agent.review.ServerRiskAggregator())
        );
        assertThat(runtime.preview("octocat", "repo", "head").warnings())
            .contains("policy_source_unavailable");
    }

    private ExternalHttpJsonResponseReader jsonReader() {
        return new ExternalHttpJsonResponseReader(new JacksonConfig().objectMapper(), new ExternalHttpResponseReader());
    }

    private ExternalCallResilience passthroughResilience() {
        ExternalCallResilience resilience = mock(ExternalCallResilience.class);
        when(resilience.github(anyString(), any())).thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
        return resilience;
    }

    private ReviewRuleRegistry registry(String id) {
        com.repoguard.agent.review.ReviewRule rule = mock(com.repoguard.agent.review.ReviewRule.class);
        when(rule.id()).thenReturn(id);
        when(rule.version()).thenReturn("v1");
        return new ReviewRuleRegistry(List.of(rule), List.of());
    }
}
