package com.repoguard.agent.notification.workflow;

import com.repoguard.agent.review.codeowners.CodeownersRecommendations;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.repoguard.agent.config.JacksonConfig;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.github.*;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.tenancy.TenantContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@SuppressWarnings("try")
class CodeownersQueryServiceTest {
    @TempDir Path directory;
    private final ReviewTaskMapper tasks = mock(ReviewTaskMapper.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final GithubIntegrationProvider github = mock(GithubIntegrationProvider.class);
    private final GithubPullRequestHeadReader heads = mock(GithubPullRequestHeadReader.class);
    private final GithubChangedFileContentReader contents = mock(GithubChangedFileContentReader.class);
    private final ExternalCallResilience resilience = mock(ExternalCallResilience.class);
    private final GithubPullRequestPathsReader paths = mock(GithubPullRequestPathsReader.class);
    private final String head = "a".repeat(40), base = "b".repeat(40);
    private CodeownersQueryService service(boolean enabled, Path file) {
        return new CodeownersQueryService(enabled, file, new JacksonConfig().objectMapper(), tasks, jdbc, github, heads, contents, resilience, paths);
    }
    private ReviewTask task() {
        var task = new ReviewTask(); task.setId(9L); task.setOrganization("owner"); task.setRepository("repo");
        task.setCurrentAttemptId(12L); task.setCommitSha(head); task.setGeneration(1L); task.setStatus("PENDING_HUMAN_REVIEW");
        task.setPrNumber(3); return task;
    }
    private Path mapping() throws Exception {
        Path file = directory.resolve("owners.json");
        Files.writeString(file, "[{\"tenantId\":7,\"repository\":\"owner/repo\",\"owners\":{\"@owner\":[11]}}]");
        return file;
    }
    @SuppressWarnings("unchecked")
    private void ready() {
        when(tasks.selectById(9L)).thenReturn(task());
        when(jdbc.query(anyString(), any(RowMapper.class), eq(7L), eq(9L), eq(12L)))
            .thenReturn(List.of(new CodeownersRecommendations.ChangedPath("src/A.java", "HIGH", 2)));
        when(jdbc.queryForList(anyString(), eq(7L))).thenReturn(List.of(Map.of("id", 11L, "username", "reviewer")));
        var settings = new GithubIntegrationSettings("GITHUB", "CONFIGURED", "https://api.github.com", null, null, "owner", "repo", 1L);
        when(github.getSettingsForRepository("owner", "repo")).thenReturn(settings);
        when(heads.fetchCodeowners(settings, "https://api.github.com", "owner", "repo", 3, head, resilience, contents))
            .thenReturn(new GithubPullRequestHeadReader.CodeownersSource(base, ".github/CODEOWNERS", "* @owner", "AVAILABLE"));
    }
    @Test void missingScopeAndDisabledConfigurationNeverReadDataOrNetwork() {
        assertThat(service(true, null).query(9).status()).isEqualTo("INVALID_SCOPE");
        try (var ignored = TenantContext.withTenant(7L)) {
            assertThat(service(false, null).query(9).status()).isEqualTo("DISABLED");
        }
        verifyNoInteractions(tasks, jdbc, github, heads, contents, paths);
    }
    @Test void scopedSourceMappingAndCurrentMembershipProduceRecommendation() throws Exception {
        ready();
        try (var ignored = TenantContext.withTenant(7L)) {
            var result = service(true, mapping()).query(9);
            assertThat(result.status()).isEqualTo("RECOMMENDATIONS");
            assertThat(result.attemptId()).isEqualTo(12L);
            assertThat(result.baseSha()).isEqualTo(base);
            assertThat(result.recommendations().candidates().getFirst().username()).isEqualTo("reviewer");
        }
    }
    @Test void absentTaskAndMappingDoNotTriggerGithub() throws Exception {
        try (var ignored = TenantContext.withTenant(7L)) {
            assertThat(service(true, null).query(9).status()).isEqualTo("NOT_FOUND");
            when(tasks.selectById(9L)).thenReturn(task());
            assertThat(service(true, null).query(9).status()).isEqualTo("NOT_CONFIGURED");
            assertThat(service(true, Path.of("relative.json")).query(9).status()).isEqualTo("INVALID_MAPPING_FILE");
        }
        verifyNoInteractions(github, heads, contents);
    }
    @Test void otherTenantCannotReuseOperatorMapping() throws Exception {
        when(tasks.selectById(9L)).thenReturn(task());
        try (var ignored = TenantContext.withTenant(8L)) {
            assertThat(service(true, mapping()).query(9).status()).isEqualTo("SCOPE_NOT_CONFIGURED");
        }
        verifyNoInteractions(jdbc, github, heads, contents, paths);
    }
    @Test @SuppressWarnings("unchecked") void storedRenameDoesNotSilentlyOmitOldOwner() throws Exception {
        ready();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(7L), eq(9L), eq(12L))).thenAnswer(call -> {
            RowMapper<CodeownersRecommendations.ChangedPath> mapper = call.getArgument(1);
            var row = mock(java.sql.ResultSet.class);
            when(row.getString("file_path")).thenReturn("renamed.java");
            when(row.getString("change_type")).thenReturn("RENAME");
            when(row.getInt("risk_rank")).thenReturn(3); when(row.getInt("finding_count")).thenReturn(2);
            return List.of(mapper.mapRow(row, 0));
        });
        when(paths.fetch(any(), anyString(), anyString(), anyString(), anyInt(), eq(head), eq(base), eq(resilience)))
            .thenReturn(new GithubPullRequestPathsReader.Paths("INVALID_CHANGES", Map.of()));
        try (var ignored = TenantContext.withTenant(7L)) { assertThat(service(true, mapping()).query(9).status()).isEqualTo("INVALID_CHANGES"); }
        verify(paths).fetch(any(), anyString(), anyString(), anyString(), anyInt(), eq(head), eq(base), eq(resilience));
    }
    @Test void limitsConcurrentQueriesAndReleasesSlots() throws Exception {
        ready(); Path file = mapping(); var query = service(true, file);
        var entered = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(heads.fetchCodeowners(any(), anyString(), anyString(), anyString(), anyInt(), anyString(), any(), any()))
            .thenAnswer(call -> {
                entered.countDown();
                if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("fixture timeout");
                return new GithubPullRequestHeadReader.CodeownersSource(base, "CODEOWNERS", "* @owner", "AVAILABLE");
            });
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.function.Supplier<CodeownersQueryService.Response> action = () -> {
                try (var scope = TenantContext.withTenant(7L)) { return query.query(9); }
            };
            var first = java.util.concurrent.CompletableFuture.supplyAsync(action, pool);
            var second = java.util.concurrent.CompletableFuture.supplyAsync(action, pool);
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try (var scope = TenantContext.withTenant(7L)) { assertThat(query.query(9).status()).isEqualTo("BUSY"); }
            release.countDown();
            assertThat(first.get(5, java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo("RECOMMENDATIONS");
            assertThat(second.get(5, java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo("RECOMMENDATIONS");
            try (var scope = TenantContext.withTenant(7L)) { assertThat(query.query(9).status()).isEqualTo("RECOMMENDATIONS"); }
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void memberRevocationRemovesRecommendation() throws Exception {
        ready(); when(jdbc.queryForList(anyString(), eq(7L))).thenReturn(List.of());
        try (var ignored = TenantContext.withTenant(7L)) {
            assertThat(service(true, mapping()).query(9).status()).isEqualTo("UNASSIGNED");
        }
    }
    @Test void changedAttemptDiscardsResultAndErrorsStaySanitized() throws Exception {
        ready(); var changed = task(); changed.setCurrentAttemptId(13L);
        when(tasks.selectById(9L)).thenReturn(task(), changed);
        try (var ignored = TenantContext.withTenant(7L)) {
            assertThat(service(true, mapping()).query(9).status()).isEqualTo("TASK_CHANGED");
            when(tasks.selectById(9L)).thenThrow(new IllegalStateException("private-fixture"));
            assertThat(service(true, mapping()).query(9).toString()).doesNotContain("private-fixture").contains("UNAVAILABLE");
        }
    }
    @Test void mappingCacheCannotHideRevocationOrChangedConfiguration() throws Exception {
        ready(); Path file = mapping(); var query = service(true, file);
        try (var ignored = TenantContext.withTenant(7L)) {
            assertThat(query.query(9).status()).isEqualTo("RECOMMENDATIONS");
            when(jdbc.queryForList(anyString(), eq(7L))).thenReturn(List.of());
            assertThat(query.query(9).status()).isEqualTo("UNASSIGNED");
            Files.writeString(file, "[]");
            assertThat(query.query(9).status()).isEqualTo("SCOPE_NOT_CONFIGURED");
            Files.delete(file);
            assertThat(query.query(9).status()).isEqualTo("UNAVAILABLE");
        }
        verify(heads, times(2)).fetchCodeowners(any(), anyString(), anyString(), anyString(), anyInt(), anyString(), any(), any());
    }
    @Test @SuppressWarnings("unchecked") void stableRenameIncludesBothOwnersAndRejectsChangedRemoteFileSet() throws Exception {
        ready();
        when(jdbc.query(anyString(), any(RowMapper.class), eq(7L), eq(9L), eq(12L))).thenAnswer(call -> {
            RowMapper<CodeownersRecommendations.ChangedPath> mapper = call.getArgument(1);
            var row = mock(java.sql.ResultSet.class); when(row.getString("file_path")).thenReturn("new/A.java");
            when(row.getString("change_type")).thenReturn("RENAME"); when(row.getInt("risk_rank")).thenReturn(3); when(row.getInt("finding_count")).thenReturn(2);
            return List.of(mapper.mapRow(row, 0));
        });
        when(heads.fetchCodeowners(any(), anyString(), anyString(), anyString(), anyInt(), anyString(), any(), any()))
            .thenReturn(new GithubPullRequestHeadReader.CodeownersSource(base, "CODEOWNERS", "new/ @owner\nold/ @previous", "AVAILABLE"));
        Path file = mapping(); Files.writeString(file, "[{\"tenantId\":7,\"repository\":\"owner/repo\",\"owners\":{\"@owner\":[11],\"@previous\":[12]}}]");
        when(jdbc.queryForList(anyString(), eq(7L))).thenReturn(List.of(Map.of("id", 11L, "username", "new-owner"), Map.of("id", 12L, "username", "old-owner")));
        when(paths.fetch(any(), anyString(), anyString(), anyString(), anyInt(), eq(head), eq(base), any()))
            .thenReturn(new GithubPullRequestPathsReader.Paths("COMPLETE", Map.of("new/A.java", new GithubPullRequestPathsReader.Change("new/A.java", "renamed", "old/A.java"))));
        try (var scope = TenantContext.withTenant(7L)) {
            var result = service(true, file).query(9); assertThat(result.status()).isEqualTo("RECOMMENDATIONS");
            assertThat(result.recommendations().candidates()).extracting(CodeownersRecommendations.Candidate::username).containsExactly("new-owner", "old-owner");
            assertThat(result.recommendations().basis()).extracting(CodeownersRecommendations.PathEvidence::path).containsExactly("new/A.java", "old/A.java");
            assertThat(result.recommendations().basis().get(1).changedFiles()).containsExactly("new/A.java");
            when(paths.fetch(any(), anyString(), anyString(), anyString(), anyInt(), eq(head), eq(base), any()))
                .thenReturn(new GithubPullRequestPathsReader.Paths("COMPLETE", Map.of("other.java", new GithubPullRequestPathsReader.Change("other.java", "modified", null))));
            assertThat(service(true, file).query(9).status()).isEqualTo("TASK_CHANGED");
        }
    }
    @Test @SuppressWarnings("unchecked") void completeQueryUsesRealHttpForBaseRulesAndBothRenamePaths() throws Exception {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new java.util.concurrent.CopyOnWriteArrayList<String>();
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI().toString()); String path = exchange.getRequestURI().getPath();
            String body = path.endsWith("/pulls/3") ? "{\"head\":{\"sha\":\"" + head + "\"},\"base\":{\"sha\":\"" + base
                + "\"},\"updated_at\":\"2026-01-01T00:00:00Z\"}" : path.endsWith("/files")
                ? "[{\"filename\":\"new/a.java\",\"status\":\"renamed\",\"previous_filename\":\"old/a.java\"}]"
                : "new/ @owner\nold/ @previous";
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        }); server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            var settings = new GithubIntegrationSettings("GITHUB", "CONFIGURED", url, null, null, "owner", "repo", 7L);
            when(github.getSettingsForRepository("owner", "repo")).thenReturn(settings); when(tasks.selectById(9L)).thenReturn(task());
            when(jdbc.query(anyString(), any(RowMapper.class), eq(7L), eq(9L), eq(12L))).thenAnswer(call -> {
                RowMapper<CodeownersRecommendations.ChangedPath> mapper = call.getArgument(1); var row = mock(java.sql.ResultSet.class);
                when(row.getString("file_path")).thenReturn("new/a.java"); when(row.getString("change_type")).thenReturn("RENAME");
                when(row.getInt("risk_rank")).thenReturn(3); when(row.getInt("finding_count")).thenReturn(2); return List.of(mapper.mapRow(row, 0));
            });
            when(jdbc.queryForList(anyString(), eq(7L))).thenReturn(List.of(Map.of("id", 11L, "username", "both-owners")), List.of());
            when(resilience.github(anyString(), any())).thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(1)).get());
            var json = new JacksonConfig().objectMapper(); var responseReader = new com.repoguard.agent.external.ExternalHttpResponseReader();
            var jsonReader = new com.repoguard.agent.external.ExternalHttpJsonResponseReader(json, responseReader);
            var policy = mock(com.repoguard.agent.external.OutboundEndpointPolicy.class);
            when(policy.validate(any(), anyString())).thenAnswer(call -> java.net.URI.create(call.getArgument(1)));
            when(policy.sameOrigin(any(), anyString(), anyString())).thenAnswer(call -> {
                var original = java.net.URI.create(call.getArgument(1)); var next = java.net.URI.create(call.getArgument(2));
                return original.getScheme().equals(next.getScheme()) && original.getAuthority().equals(next.getAuthority());
            });
            var actualHeads = new GithubPullRequestHeadReader(org.springframework.web.client.RestClient.builder(), jsonReader, policy);
            var actualContents = new GithubChangedFileContentReader(org.springframework.web.client.RestClient.builder(), responseReader, policy);
            var actualPaths = new GithubPullRequestPathsReader(new GithubPaginator(org.springframework.web.client.RestClient.builder(), jsonReader, policy), actualHeads);
            Path file = mapping(); Files.writeString(file, "[{\"tenantId\":7,\"repository\":\"owner/repo\",\"owners\":{\"@owner\":[11],\"@previous\":[11]}}]");
            var query = new CodeownersQueryService(true, file, json, tasks, jdbc, github, actualHeads, actualContents, resilience, actualPaths);
            try (var scope = TenantContext.withTenant(7L)) {
                var result = query.query(9); assertThat(result.status()).isEqualTo("RECOMMENDATIONS");
                var candidate = result.recommendations().candidates().getFirst(); assertThat(candidate.paths()).containsExactly("new/a.java", "old/a.java");
                assertThat(candidate.coveredFiles()).isEqualTo(1); assertThat(candidate.findingCount()).isEqualTo(2); assertThat(candidate.riskScore()).isEqualTo(4);
                assertThat(requests).containsExactly("/repos/owner/repo/pulls/3", "/repos/owner/repo/contents/.github/CODEOWNERS?ref=" + base,
                    "/repos/owner/repo/pulls/3", "/repos/owner/repo/pulls/3", "/repos/owner/repo/pulls/3/files?per_page=100&page=1", "/repos/owner/repo/pulls/3");
                assertThat(query.query(9).status()).isEqualTo("UNASSIGNED"); // Eligibility is refreshed even when mapping parsing is cached.
            }
        } finally { server.stop(0); }
    }
}
