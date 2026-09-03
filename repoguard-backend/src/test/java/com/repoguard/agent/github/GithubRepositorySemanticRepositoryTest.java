package com.repoguard.agent.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.config.LlmReviewContextProperties;
import com.repoguard.agent.config.ReviewContextProperties;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.external.ExternalHttpJsonResponseReader;
import com.repoguard.agent.external.ExternalHttpResponseReader;
import com.repoguard.agent.external.OutboundEndpointPolicy;
import com.repoguard.agent.review.ChangedFileContext;
import com.repoguard.agent.review.PullRequestChangedFile;
import com.repoguard.agent.review.PullRequestDiff;
import com.repoguard.agent.review.RepositorySemanticFile;
import com.repoguard.agent.review.RepositorySemanticLimitation;
import com.repoguard.agent.review.RepositorySemanticRepository;
import com.repoguard.agent.review.RepositorySemanticSnapshot;
import com.repoguard.agent.review.ReviewFilePolicy;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class GithubRepositorySemanticRepositoryTest {

    @Test
    void fetchesDefaultBranchTreeAndBoundedFileContents() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/octo/repo", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = path.endsWith("/git/trees/main")
                ? "{\"truncated\":false,\"tree\":["
                    + "{\"path\":\"src/main/java/OrderService.java\",\"type\":\"blob\"},"
                    + "{\"path\":\"src/main/java/OrderFacade.java\",\"type\":\"blob\"},"
                    + "{\"path\":\"src/main/resources/application.yml\",\"type\":\"blob\"},"
                    + "{\"path\":\"docs/README.md\",\"type\":\"blob\"}]}"
                : "{\"default_branch\":\"main\"}";
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            GithubIntegrationProvider integrationProvider = mock(GithubIntegrationProvider.class);
            GithubChangedFileContentReader contentReader = mock(GithubChangedFileContentReader.class);
            ExternalCallResilience resilience = passthroughResilience();
            OutboundEndpointPolicy endpointPolicy = mock(OutboundEndpointPolicy.class);
            doReturn(URI.create("https://api.github.com")).when(endpointPolicy).validate(any(), any());
            GithubIntegrationSettings settings = new GithubIntegrationSettings(
                "GITHUB", "CONFIGURED", "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-token", null, "octo", "repo", 1L
            );
            when(integrationProvider.getSettingsForRepository("octo", "repo")).thenReturn(settings);
            when(contentReader.fetch(
                eq(settings), eq(settings.baseUrl()), eq("octo"), eq("repo"), eq("main"),
                eq("src/main/java/OrderFacade.java"), eq(resilience)
            )).thenReturn("class OrderFacade { OrderService service; }");
            when(contentReader.fetch(
                eq(settings), eq(settings.baseUrl()), eq("octo"), eq("repo"), eq("main"),
                eq("src/main/resources/application.yml"), eq(resilience)
            )).thenReturn("security:\n  role: ADMIN");

            GithubRepositorySemanticRepository repository = new GithubRepositorySemanticRepository(
                integrationProvider,
                contentReader,
                resilience,
                new ExternalHttpJsonResponseReader(new ObjectMapper(), new ExternalHttpResponseReader()),
                endpointPolicy,
                RestClient.builder(),
                new LlmReviewContextProperties(),
                new ReviewFilePolicy(new ReviewContextProperties())
            );
            PullRequestDiff diff = new PullRequestDiff(
                "octo", "repo", 7, "head", List.of(new PullRequestChangedFile(
                    "src/main/java/OrderService.java", "modified", 1, 0,
                    "@@ -1,0 +1,1 @@\n+class OrderService {}",
                    ChangedFileContext.available("src/main/java/OrderService.java", "head", "class OrderService {}")
                ))
            );

            RepositorySemanticSnapshot snapshot = repository.fetch(
                diff, java.util.Set.of("OrderService")
            );

            assertThat(snapshot.defaultBranch()).isEqualTo("main");
            assertThat(snapshot.files()).extracting(RepositorySemanticFile::path)
                .containsExactly("src/main/java/OrderFacade.java", "src/main/resources/application.yml");
            assertThat(snapshot.summary()).contains("deterministic=true", "candidates=2");
            assertThat(snapshot.limitations()).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void skipsUnconfiguredRepositoriesAndReportsMetadataFailure() {
        GithubIntegrationProvider integrationProvider = mock(GithubIntegrationProvider.class);
        ExternalCallResilience resilience = mock(ExternalCallResilience.class);
        when(integrationProvider.getSettingsForRepository("octo", "repo"))
            .thenReturn(GithubIntegrationSettings.empty());
        GithubRepositorySemanticRepository repository = new GithubRepositorySemanticRepository(
            integrationProvider,
            mock(GithubChangedFileContentReader.class),
            resilience,
            new ExternalHttpJsonResponseReader(new ObjectMapper(), new ExternalHttpResponseReader()),
            mock(OutboundEndpointPolicy.class),
            RestClient.builder(),
            new LlmReviewContextProperties(),
            new ReviewFilePolicy(new ReviewContextProperties())
        );
        PullRequestDiff diff = new PullRequestDiff("octo", "repo", 1, "head", List.of());

        assertThat(repository.fetch(diff, java.util.Set.of("OrderService")).summary())
            .isEqualTo("github_integration_not_configured");

        when(integrationProvider.getSettingsForRepository("octo", "repo"))
            .thenReturn(new GithubIntegrationSettings(
                "GITHUB", "CONFIGURED", "https://api.github.com", null, null, "octo", "repo", 1L
            ));
        when(resilience.github(eq("fetch_repository_metadata"), any()))
            .thenThrow(new IllegalArgumentException("metadata unavailable"));
        assertThat(repository.fetch(diff, java.util.Set.of("OrderService")).summary())
            .isEqualTo("unavailable; reason=default_branch_unavailable:IllegalArgumentException");
    }

    @Test
    void reportsFileLimitsFetchFailuresAndFiltersUnsafeTreeEntries() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/octo/repo", exchange -> {
            String body = exchange.getRequestURI().getPath().endsWith("/git/trees/main")
                ? "{\"truncated\":false,\"tree\":["
                    + "{\"path\":\"src/main/java/OrderServiceSupport/Helper.java\",\"type\":\"blob\"},"
                    + "{\"path\":\"src/main/java/Failure.java\",\"type\":\"blob\"},"
                    + "{\"path\":\"src/main/resources/application.yml\",\"type\":\"blob\"},"
                    + "{\"path\":\"../escape.java\",\"type\":\"blob\"},"
                    + "{\"path\":\"/absolute.java\",\"type\":\"blob\"},"
                    + "{\"path\":\"docs/README.md\",\"type\":\"blob\"},"
                    + "{\"path\":\"image.png\",\"type\":\"blob\"}]}"
                : "{\"default_branch\":\"main\"}";
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            GithubIntegrationProvider integrationProvider = mock(GithubIntegrationProvider.class);
            GithubChangedFileContentReader contentReader = mock(GithubChangedFileContentReader.class);
            ExternalCallResilience resilience = passthroughResilience();
            OutboundEndpointPolicy endpointPolicy = mock(OutboundEndpointPolicy.class);
            doReturn(URI.create("https://api.github.com")).when(endpointPolicy).validate(any(), any());
            GithubIntegrationSettings settings = new GithubIntegrationSettings(
                "GITHUB", "CONFIGURED", "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-token", null, "octo", "repo", 1L
            );
            when(integrationProvider.getSettingsForRepository("octo", "repo")).thenReturn(settings);
            when(contentReader.fetch(
                eq(settings), eq(settings.baseUrl()), eq("octo"), eq("repo"), eq("main"),
                eq("src/main/java/OrderServiceSupport/Helper.java"), eq(resilience)
            )).thenReturn("x".repeat(5_000));
            when(contentReader.fetch(
                eq(settings), eq(settings.baseUrl()), eq("octo"), eq("repo"), eq("main"),
                eq("src/main/java/Failure.java"), eq(resilience)
            )).thenThrow(new IllegalStateException("content unavailable"));
            when(contentReader.fetch(
                eq(settings), eq(settings.baseUrl()), eq("octo"), eq("repo"), eq("main"),
                eq("src/main/resources/application.yml"), eq(resilience)
            )).thenReturn("security:\n  role: ADMIN");

            LlmReviewContextProperties properties = new LlmReviewContextProperties();
            properties.setSemanticIndexMaxFileBytes(4_096);
            GithubRepositorySemanticRepository repository = newRepository(
                integrationProvider, contentReader, resilience, endpointPolicy, properties
            );
            PullRequestDiff diff = new PullRequestDiff(
                "octo", "repo", 7, "head", List.of(new PullRequestChangedFile(
                    "src/main/java/OrderService.java", "modified", 1, 0,
                    "@@ -1,0 +1,1 @@\n+class OrderService {}",
                    ChangedFileContext.available("src/main/java/OrderService.java", "head", "class OrderService {}")
                ))
            );

            RepositorySemanticSnapshot snapshot = repository.fetch(diff, Set.of("OrderService"));

            assertThat(snapshot.files()).extracting(RepositorySemanticFile::path)
                .containsExactly("src/main/resources/application.yml");
            assertThat(snapshot.limitations())
                .extracting(RepositorySemanticLimitation::status)
                .containsExactlyInAnyOrder("TOO_LARGE", "UNAVAILABLE");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void truncatesByFileCountAndTotalBytes() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/octo/repo", exchange -> {
            String body = exchange.getRequestURI().getPath().endsWith("/git/trees/main")
                ? "{\"truncated\":false,\"tree\":["
                    + "{\"path\":\"src/main/java/OrderServiceSupport/Helper.java\",\"type\":\"blob\"},"
                    + "{\"path\":\"src/main/java/OrderServiceFactory.java\",\"type\":\"blob\"}]}"
                : "{\"default_branch\":\"main\"}";
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            GithubIntegrationProvider integrationProvider = mock(GithubIntegrationProvider.class);
            GithubChangedFileContentReader contentReader = mock(GithubChangedFileContentReader.class);
            ExternalCallResilience resilience = passthroughResilience();
            OutboundEndpointPolicy endpointPolicy = mock(OutboundEndpointPolicy.class);
            doReturn(URI.create("https://api.github.com")).when(endpointPolicy).validate(any(), any());
            GithubIntegrationSettings settings = new GithubIntegrationSettings(
                "GITHUB", "CONFIGURED", "http://127.0.0.1:" + server.getAddress().getPort(),
                null, null, "octo", "repo", 1L
            );
            when(integrationProvider.getSettingsForRepository("octo", "repo")).thenReturn(settings);
            when(contentReader.fetch(any(), any(), eq("octo"), eq("repo"), eq("main"), any(), eq(resilience)))
                .thenReturn("x".repeat(10_000));
            PullRequestDiff diff = new PullRequestDiff(
                "octo", "repo", 7, "head", List.of(new PullRequestChangedFile(
                    "src/main/java/OrderService.java", "modified", 1, 0,
                    "@@ -1,0 +1,1 @@\n+class OrderService {}",
                    ChangedFileContext.available("src/main/java/OrderService.java", "head", "class OrderService {}")
                ))
            );

            LlmReviewContextProperties countProperties = new LlmReviewContextProperties();
            countProperties.setSemanticIndexMaxFiles(1);
            RepositorySemanticSnapshot countLimited = newRepository(
                integrationProvider, contentReader, resilience, endpointPolicy, countProperties
            ).fetch(diff, Set.of("OrderService"));
            assertThat(countLimited.truncated()).isTrue();

            LlmReviewContextProperties bytesProperties = new LlmReviewContextProperties();
            bytesProperties.setSemanticIndexMaxTotalBytes(16_384);
            GithubRepositorySemanticRepository bytesRepository = newRepository(
                integrationProvider, contentReader, resilience, endpointPolicy, bytesProperties
            );
            RepositorySemanticSnapshot bytesLimited = bytesRepository.fetch(diff, Set.of("OrderService"));
            assertThat(bytesLimited.truncated()).isTrue();
            assertThat(bytesLimited.files()).hasSize(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void degradesWhenIntegrationTreeOrBranchIsUnavailable() throws Exception {
        GithubIntegrationProvider integrationProvider = mock(GithubIntegrationProvider.class);
        ExternalCallResilience resilience = mock(ExternalCallResilience.class);
        OutboundEndpointPolicy endpointPolicy = mock(OutboundEndpointPolicy.class);
        GithubChangedFileContentReader contentReader = mock(GithubChangedFileContentReader.class);
        LlmReviewContextProperties properties = new LlmReviewContextProperties();
        PullRequestDiff diff = new PullRequestDiff("octo", "repo", 1, "head", List.of());

        doThrow(new IllegalStateException("settings down"))
            .when(integrationProvider).getSettingsForRepository("octo", "repo");
        assertThat(newRepository(integrationProvider, contentReader, resilience, endpointPolicy, properties)
            .fetch(diff, Set.of()).summary()).contains("integration_settings_unavailable");

        GithubIntegrationSettings settings = new GithubIntegrationSettings(
            "GITHUB", "CONFIGURED", "https://api.github.com", null, null, "octo", "repo", 1L
        );
        doReturn(settings).when(integrationProvider).getSettingsForRepository("octo", "repo");
        doReturn(new ObjectMapper().readTree("{\"default_branch\":\"main\"}"))
            .when(resilience).github(eq("fetch_repository_metadata"), any());
        doThrow(new IllegalArgumentException("tree down"))
            .when(resilience).github(eq("fetch_default_branch_tree"), any());
        assertThat(newRepository(integrationProvider, contentReader, resilience, endpointPolicy, properties)
            .fetch(diff, Set.of()).summary()).contains("default_branch_tree_unavailable");

        doAnswer(invocation -> {
            java.util.function.Supplier<?> supplier = invocation.getArgument(1);
            return supplier.get();
        }).when(resilience).github(eq("fetch_default_branch_tree"), any());
        // A metadata response without default_branch is handled before the tree request.
        doReturn(new ObjectMapper().readTree("{}")).when(resilience)
            .github(eq("fetch_repository_metadata"), any());
        assertThat(newRepository(integrationProvider, contentReader, resilience, endpointPolicy, properties)
            .fetch(diff, Set.of()).summary()).contains("default_branch_missing");
    }

    @Test
    void handlesMissingDiffAndTreeAndPathTokenScoring() throws Exception {
        GithubIntegrationProvider integrationProvider = mock(GithubIntegrationProvider.class);
        ExternalCallResilience resilience = mock(ExternalCallResilience.class);
        OutboundEndpointPolicy endpointPolicy = mock(OutboundEndpointPolicy.class);
        GithubChangedFileContentReader contentReader = mock(GithubChangedFileContentReader.class);
        LlmReviewContextProperties properties = new LlmReviewContextProperties();
        GithubRepositorySemanticRepository repository = newRepository(
            integrationProvider, contentReader, resilience, endpointPolicy, properties
        );
        assertThat(repository.fetch(null, Set.of()).summary()).isEqualTo("missing_repository");

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/repos/octo/repo", exchange -> {
            String body = exchange.getRequestURI().getPath().endsWith("/git/trees/main")
                ? "{\"truncated\":false}"
                : "{\"default_branch\":\"main\"}";
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            GithubIntegrationSettings settings = new GithubIntegrationSettings(
                "GITHUB", "CONFIGURED", "http://127.0.0.1:" + server.getAddress().getPort(),
                null, null, "octo", "repo", 1L
            );
            when(integrationProvider.getSettingsForRepository("octo", "repo")).thenReturn(settings);
            ExternalCallResilience pass = passthroughResilience();
            GithubRepositorySemanticRepository emptyTreeRepository = newRepository(
                integrationProvider, contentReader, pass, endpointPolicy, properties
            );
            PullRequestDiff diff = new PullRequestDiff(
                "octo", "repo", 1, "head", List.of(new PullRequestChangedFile(
                    "src/main/java/OrderService.java", "modified", 1, 0,
                    "@@ -1,0 +1,1 @@\n+class OrderService {}",
                    ChangedFileContext.available("src/main/java/OrderService.java", "head", "class OrderService {}")
                ))
            );
            assertThat(emptyTreeRepository.fetch(diff, Set.of("OrderService")).files()).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    private GithubRepositorySemanticRepository newRepository(
        GithubIntegrationProvider integrationProvider,
        GithubChangedFileContentReader contentReader,
        ExternalCallResilience resilience,
        OutboundEndpointPolicy endpointPolicy,
        LlmReviewContextProperties properties
    ) {
        return new GithubRepositorySemanticRepository(
            integrationProvider,
            contentReader,
            resilience,
            new ExternalHttpJsonResponseReader(new ObjectMapper(), new ExternalHttpResponseReader()),
            endpointPolicy,
            RestClient.builder(),
            properties,
            new ReviewFilePolicy(new ReviewContextProperties())
        );
    }

    private ExternalCallResilience passthroughResilience() {
        ExternalCallResilience resilience = mock(ExternalCallResilience.class);
        when(resilience.github(any(), any())).thenAnswer(invocation -> {
            java.util.function.Supplier<?> supplier = invocation.getArgument(1);
            return supplier.get();
        });
        return resilience;
    }
}
