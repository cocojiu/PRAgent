package com.repoguard.agent.notification.workflow;

import com.repoguard.agent.review.codeowners.CodeownersRecommendations;
import com.repoguard.agent.review.codeowners.CodeownersMappingCatalog;
import com.repoguard.agent.review.codeowners.CodeownersMappingCache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.dto.ReviewAssignmentSnapshot;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.github.GithubChangedFileContentReader;
import com.repoguard.agent.github.GithubIntegrationProvider;
import com.repoguard.agent.github.GithubPullRequestHeadReader;
import com.repoguard.agent.github.GithubPullRequestPathsReader;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.ArrayList;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.tenancy.TenantContext;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import org.springframework.jdbc.core.JdbcTemplate;

/** Read-only query orchestration. Configuration is operator-owned, never supplied by an HTTP caller. */
public final class CodeownersQueryService {
    private final boolean enabled;
    private final Path mappingFile;
    private final CodeownersMappingCache mappingCache;
    private final ReviewTaskMapper tasks;
    private final JdbcTemplate jdbc;
    private final GithubIntegrationProvider github;
    private final GithubPullRequestHeadReader heads;
    private final GithubChangedFileContentReader contents;
    private final GithubPullRequestPathsReader pathsReader;
    private final ExternalCallResilience resilience;
    private final Semaphore queries = new Semaphore(2);

    public CodeownersQueryService(boolean enabled, Path mappingFile, ObjectMapper json, ReviewTaskMapper tasks,
                                  JdbcTemplate jdbc, GithubIntegrationProvider github,
                                  GithubPullRequestHeadReader heads, GithubChangedFileContentReader contents,
                                  ExternalCallResilience resilience, GithubPullRequestPathsReader pathsReader) {
        this.pathsReader = pathsReader;
        this.enabled = enabled; this.mappingFile = mappingFile; this.mappingCache = new CodeownersMappingCache(json); this.tasks = tasks;
        this.jdbc = jdbc; this.github = github; this.heads = heads; this.contents = contents; this.resilience = resilience;
    }
    public record Response(String status, Long taskId, Long attemptId, String headSha, String baseSha,
                           String sourcePath, CodeownersRecommendations.Result recommendations, ReviewAssignmentSnapshot assignment) {
        public Response(String status, Long taskId, Long attemptId, String headSha, String baseSha,
                        String sourcePath, CodeownersRecommendations.Result recommendations) {
            this(status, taskId, attemptId, headSha, baseSha, sourcePath, recommendations, null);
        }
    }

    public Response query(long taskId) {
        Long tenant = TenantContext.currentTenantId();
        if (tenant == null || tenant < 1 || taskId < 1) return empty("INVALID_SCOPE", taskId);
        if (!enabled) return empty("DISABLED", taskId);
        if (!queries.tryAcquire()) return empty("BUSY", taskId);
        try {
            ReviewTask task = tasks.selectById(taskId);
            if (task == null) return empty("NOT_FOUND", taskId);
            if (!"PENDING_HUMAN_REVIEW".equals(task.getStatus()) || task.getCurrentAttemptId() == null
                || task.getCommitSha() == null || !task.getCommitSha().matches("[0-9a-fA-F]{40}")) {
                return empty("TASK_NOT_ELIGIBLE", taskId);
            }
            String repository = task.getOrganization() + "/" + task.getRepository();
            var mapping = readMapping(tenant, repository);
            if (!mapping.status().equals("CONFIGURED")) return empty(mapping.status(), taskId);
            var stored = changedPaths(tenant, task);
            List<CodeownersRecommendations.ChangedPath> files = stored.paths();
            if (files.isEmpty()) return empty("NO_CHANGED_FILES", taskId);
            if (files.size() > 200) return empty("BUDGET_EXCEEDED", taskId);
            var settings = github.getSettingsForRepository(task.getOrganization(), task.getRepository());
            if (settings == null || !settings.exists()) return empty("GITHUB_NOT_CONFIGURED", taskId);
            String baseUrl = settings.baseUrl() == null || settings.baseUrl().isBlank()
                ? "https://api.github.com" : settings.baseUrl();
            var source = heads.fetchCodeowners(settings, baseUrl, task.getOrganization(), task.getRepository(),
                task.getPrNumber(), task.getCommitSha(), resilience, contents);
            if (!source.status().equals("AVAILABLE")) return empty(source.status(), taskId);
            if (!stored.renamed().isEmpty()) {
                var remote = pathsReader.fetch(settings, baseUrl, task.getOrganization(), task.getRepository(), task.getPrNumber(),
                    task.getCommitSha(), source.baseSha(), resilience);
                if (!"COMPLETE".equals(remote.status())) return empty(remote.status(), taskId);
                if (!remote.files().keySet().equals(files.stream().map(CodeownersRecommendations.ChangedPath::path)
                    .collect(java.util.stream.Collectors.toSet()))) return empty("TASK_CHANGED", taskId);
                List<CodeownersRecommendations.ChangedPath> expanded = new ArrayList<>(files);
                for (var file : files) {
                    var metadata = remote.files().get(file.path());
                    if (stored.renamed().contains(file.path()) != "renamed".equals(metadata.status())) return empty("INVALID_CHANGES", taskId);
                    if (stored.renamed().contains(file.path())) expanded.add(new CodeownersRecommendations.ChangedPath(
                        metadata.previousFilename(), file.risk(), file.findingCount(), file.path()));
                }
                files = expanded;
            }
            // Read eligibility after network I/O. Assignment must recheck it transactionally as well.
            Map<Long, String> members = eligibleMembers(tenant);
            if (members.size() > 3000) return empty("BUDGET_EXCEEDED", taskId);
            var result = CodeownersRecommendations.recommend(source.content(), files, mapping.owners(), members);
            ReviewTask current = tasks.selectById(taskId);
            if (current == null || !Objects.equals(task.getCurrentAttemptId(), current.getCurrentAttemptId())
                || !Objects.equals(task.getCommitSha(), current.getCommitSha())
                || !Objects.equals(task.getGeneration(), current.getGeneration())
                || !Objects.equals(task.getStatus(), current.getStatus())
                || !ReviewAssignmentSnapshot.from(task).matches(current)) return empty("TASK_CHANGED", taskId);
            return new Response(result.status(), taskId, task.getCurrentAttemptId(), task.getCommitSha(),
                source.baseSha(), source.path(), result, ReviewAssignmentSnapshot.from(task));
        } catch (Exception failure) {
            return empty("UNAVAILABLE", taskId); // No paths, source text, tokens or exception details in failures.
        } finally { queries.release(); }
    }
    private CodeownersMappingCatalog.Scope readMapping(long tenant, String repository) throws java.io.IOException {
        if (mappingFile == null) return new CodeownersMappingCatalog.Scope("NOT_CONFIGURED", Map.of());
        if (!mappingFile.isAbsolute() || !mappingFile.normalize().equals(mappingFile.toRealPath())
            || !Files.isRegularFile(mappingFile, LinkOption.NOFOLLOW_LINKS)) {
            return new CodeownersMappingCatalog.Scope("INVALID_MAPPING_FILE", Map.of());
        }
        byte[] bytes;
        try (var input = Files.newInputStream(mappingFile, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(262145); }
        return mappingCache.resolve(bytes, tenant, repository);
    }
    private record StoredPaths(List<CodeownersRecommendations.ChangedPath> paths, Set<String> renamed) { }
    private StoredPaths changedPaths(long tenant, ReviewTask task) {
        Set<String> renamed = new LinkedHashSet<>();
        List<CodeownersRecommendations.ChangedPath> paths = jdbc.query("""
            select file.file_path, file.change_type,
                   coalesce(max(case finding.severity when 'CRITICAL' then 4 when 'HIGH' then 3
                       when 'MEDIUM' then 2 else 1 end), 1) as risk_rank,
                   count(finding.id) as finding_count
              from changed_file file
              left join review_finding finding on finding.tenant_id = file.tenant_id
               and finding.task_id = file.task_id and finding.attempt_id = file.attempt_id
               and finding.file_path = file.file_path and finding.current_attempt = 1 and finding.category = 'FINDING'
             where file.tenant_id = ? and file.task_id = ? and file.attempt_id = ? and file.current_attempt = 1
             group by file.id, file.file_path, file.change_type order by file.id limit 201
            """, (row, index) -> {
                String path = row.getString("file_path");
                if ("RENAME".equalsIgnoreCase(row.getString("change_type"))) renamed.add(path);
                return new CodeownersRecommendations.ChangedPath(path,
                    switch (row.getInt("risk_rank")) { case 4 -> "CRITICAL"; case 3 -> "HIGH"; case 2 -> "MEDIUM"; default -> "LOW"; },
                    row.getInt("finding_count"));
            }, tenant, task.getId(), task.getCurrentAttemptId());
        return new StoredPaths(paths, Set.copyOf(renamed));
    }
    private Map<Long, String> eligibleMembers(long tenant) {
        var rows = jdbc.queryForList("""
            select account.id, account.username from tenant_membership membership
              join user_account account on account.id = membership.user_id
              join tenant on tenant.id = membership.tenant_id
             where membership.tenant_id = ? and account.status = 'ACTIVE' and tenant.status = 'ACTIVE'
               and membership.role in ('ADMIN','PLATFORM_ADMIN','TENANT_ADMIN','REVIEWER')
             order by account.id limit 3001
            """, tenant);
        Map<Long, String> members = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) members.put(((Number) row.get("id")).longValue(), (String) row.get("username"));
        return members;
    }
    private Response empty(String status, long taskId) { return new Response(status, taskId, null, null, null, null, null); }
}
