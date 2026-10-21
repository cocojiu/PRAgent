package com.repoguard.agent.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.repoguard.agent.config.LlmReviewContextProperties;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.external.ExternalHttpJsonResponseReader;
import com.repoguard.agent.external.ExternalHttpResponseProfile;
import com.repoguard.agent.external.ExternalHttpResponseTooLargeException;
import com.repoguard.agent.external.OutboundEndpointPolicy;
import com.repoguard.agent.external.OutboundEndpointType;
import com.repoguard.agent.review.PullRequestChangedFile;
import com.repoguard.agent.review.PullRequestDiff;
import com.repoguard.agent.review.RepositorySemanticFile;
import com.repoguard.agent.review.RepositorySemanticLimitation;
import com.repoguard.agent.review.RepositorySemanticRepository;
import com.repoguard.agent.review.RepositorySemanticSnapshot;
import com.repoguard.agent.review.ReviewFilePolicy;
import com.repoguard.agent.review.ReviewDeadline;
import com.repoguard.agent.tenancy.TenantContext;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
class GithubRepositorySemanticRepository implements RepositorySemanticRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(GithubRepositorySemanticRepository.class);
    private static final String DEFAULT_GITHUB_BASE_URL = "https://api.github.com";
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
        ".java", ".kt", ".kts", ".go", ".py", ".rb", ".rs", ".cs", ".c", ".cc", ".cpp", ".h", ".hpp",
        ".js", ".jsx", ".ts", ".tsx", ".vue", ".scala", ".groovy", ".sql", ".yml", ".yaml", ".properties",
        ".toml", ".xml", ".json", ".conf", ".ini"
    );

    private final GithubIntegrationProvider integrationProvider;
    private final GithubChangedFileContentReader contentReader;
    private final ExternalCallResilience resilience;
    private final ExternalHttpJsonResponseReader jsonReader;
    private final OutboundEndpointPolicy endpointPolicy;
    private final RestClient restClient;
    private final LlmReviewContextProperties properties;
    private final ReviewFilePolicy filePolicy;
    private final Cache<CacheKey, RepositorySemanticSnapshot> cache;
    private io.micrometer.core.instrument.MeterRegistry meterRegistry;

    @Autowired
    GithubRepositorySemanticRepository(
        GithubIntegrationProvider integrationProvider,
        GithubChangedFileContentReader contentReader,
        ExternalCallResilience resilience,
        ExternalHttpJsonResponseReader jsonReader,
        OutboundEndpointPolicy endpointPolicy,
        RestClient.Builder restClientBuilder,
        LlmReviewContextProperties properties,
        ReviewFilePolicy filePolicy
    ) {
        this.integrationProvider = Objects.requireNonNull(integrationProvider, "integrationProvider");
        this.contentReader = Objects.requireNonNull(contentReader, "contentReader");
        this.resilience = Objects.requireNonNull(resilience, "resilience");
        this.jsonReader = Objects.requireNonNull(jsonReader, "jsonReader");
        this.endpointPolicy = Objects.requireNonNull(endpointPolicy, "endpointPolicy");
        this.restClient = GithubRestClientFactory.build(Objects.requireNonNull(restClientBuilder, "restClientBuilder"));
        this.properties = Objects.requireNonNull(properties, "properties");
        this.filePolicy = Objects.requireNonNull(filePolicy, "filePolicy");
        this.cache = Caffeine.newBuilder()
            .maximumWeight(Math.min(256L * 1024 * 1024,
                Math.multiplyExact(properties.getSemanticIndexCacheMaximumSize(), (long) properties.getSemanticIndexMaxTotalBytes())))
            .weigher((CacheKey key, RepositorySemanticSnapshot value) -> (int) Math.min(Integer.MAX_VALUE,
                Math.max(properties.getSemanticIndexMaxTotalBytes(), 1024L + value.files().stream()
                    .mapToLong(file -> 128L + 2L * (file.path().length() + file.content().length())).sum())))
            .expireAfterWrite(Duration.ofSeconds(properties.getSemanticIndexCacheTtlSeconds()))
            .recordStats()
            .build();
    }

    @Autowired
    void bindCacheMetrics(io.micrometer.core.instrument.MeterRegistry registry) {
        meterRegistry = registry;
        io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics.monitor(registry, cache, "repository_semantic");
    }

    @Override
    public RepositorySemanticSnapshot fetch(PullRequestDiff diff, Set<String> changedSymbols) {
        ReviewDeadline deadline = ReviewDeadline.startingNow(Duration.ofMillis(properties.getSemanticIndexTimeoutMs()));
        if (diff == null || !StringUtils.hasText(diff.owner()) || !StringUtils.hasText(diff.repository())) {
            return RepositorySemanticSnapshot.empty("missing_repository");
        }
        String owner = diff.owner().trim();
        String repository = diff.repository().trim();
        GithubIntegrationSettings settings;
        try {
            settings = integrationProvider.getSettingsForRepository(owner, repository);
        } catch (RuntimeException ex) {
            return degraded("integration_settings_unavailable:" + ex.getClass().getSimpleName());
        }
        if (settings == null || !settings.exists()) {
            return RepositorySemanticSnapshot.empty("github_integration_not_configured");
        }
        String baseUrl = StringUtils.hasText(settings.baseUrl()) ? settings.baseUrl().trim() : DEFAULT_GITHUB_BASE_URL;
        Set<String> symbols = changedSymbols == null ? Set.of() : Set.copyOf(changedSymbols);
        try {
            // Resolve against GitHub even on cache hits: revoked credentials must not reuse cached content.
            String branch = text(getJson(settings, baseUrl, owner, repository, "", "fetch_repository_metadata", deadline)
                .path("default_branch"));
            if (!StringUtils.hasText(branch)) return degraded("default_branch_missing");
            JsonNode commit = getJson(settings, baseUrl, owner, repository, branch, "resolve_semantic_commit", deadline);
            String commitSha = text(commit.path("sha"));
            String treeSha = text(commit.path("commit").path("tree").path("sha"));
            if (!commitSha.matches("[0-9a-fA-F]{40,64}") || !treeSha.matches("[0-9a-fA-F]{40,64}")) {
                return degraded("semantic_commit_missing");
            }
            String selection = diff.files().stream().map(PullRequestChangedFile::filename)
                .filter(StringUtils::hasText).map(this::normalize).distinct().sorted()
                .collect(java.util.stream.Collectors.joining("\n"));
            CacheKey key = new CacheKey(TenantContext.currentTenantIdOrDefault(), settings.id(),
                digest(settings.token() == null ? "" : settings.token()), baseUrl, owner, repository, commitSha,
                symbols.stream().sorted().toList(), digest(selection),
                List.of(properties.getSemanticIndexMaxFiles(), properties.getSemanticIndexMaxFileBytes(),
                    properties.getSemanticIndexMaxTotalBytes()));
            RepositorySemanticSnapshot snapshot = cache.get(key, ignored ->
                fetchUncached(settings, baseUrl, owner, repository, diff, symbols, branch, commitSha, treeSha, deadline));
            deadline.requireRemaining("semantic_context_cache");
            if (snapshot.limitations().stream().anyMatch(item -> "UNAVAILABLE".equals(item.status()))) cache.invalidate(key);
            return snapshot;
        } catch (RuntimeException ex) {
            LOGGER.warn(
                "GitHub repository semantic context degraded repository={}/{} operation=repository_semantic_context exceptionType={}",
                owner, repository, ex.getClass().getName()
            );
            return degraded("semantic_context_unavailable:" + ex.getClass().getSimpleName());
        }
    }

    private RepositorySemanticSnapshot fetchUncached(
        GithubIntegrationSettings settings,
        String baseUrl,
        String owner,
        String repository,
        PullRequestDiff diff,
        Set<String> symbols,
        String branch,
        String commitSha,
        String treeSha,
        ReviewDeadline deadline
    ) {
        JsonNode root;
        try {
            root = getJson(settings, baseUrl, owner, repository, treeSha, "fetch_default_branch_tree", deadline);
        } catch (RuntimeException ex) {
            return degraded("default_branch_tree_unavailable:" + ex.getClass().getSimpleName());
        }
        List<TreeEntry> entries = treeEntries(root);
        List<Candidate> candidates = candidates(entries, diff, symbols);
        List<RepositorySemanticFile> files = new ArrayList<>();
        List<RepositorySemanticLimitation> limitations = new ArrayList<>();
        int retainedBytes = 0;
        int requested = 0;
        boolean truncated = root != null && root.path("truncated").asBoolean(false);
        for (Candidate candidate : candidates) {
            if (requested >= properties.getSemanticIndexMaxFiles() || deadline.exhausted()
                || retainedBytes >= properties.getSemanticIndexMaxTotalBytes()) {
                if (meterRegistry != null) meterRegistry.counter("repoguard.review.semantic.limit", "reason",
                    deadline.exhausted() ? "deadline" : requested >= properties.getSemanticIndexMaxFiles() ? "files" : "bytes").increment();
                truncated = true;
                break;
            }
            requested++;
            try {
                int byteBudget = Math.min(properties.getSemanticIndexMaxFileBytes(), properties.getSemanticIndexMaxTotalBytes() - retainedBytes);
                String content = observe("fetch_semantic_file_context", () -> contentReader.fetch(
                    settings, baseUrl, owner, repository, commitSha, candidate.path(), resilience, deadline, byteBudget));
                int bytes = content.getBytes(StandardCharsets.UTF_8).length;
                if (meterRegistry != null) meterRegistry.summary("repoguard.review.semantic.file.bytes").record(bytes);
                if (bytes > properties.getSemanticIndexMaxFileBytes()) {
                    limitations.add(new RepositorySemanticLimitation(
                        candidate.path(), "TOO_LARGE", "semantic_context_max_file_bytes"
                    ));
                    continue;
                }
                if (retainedBytes + bytes > properties.getSemanticIndexMaxTotalBytes()) {
                    truncated = true;
                    break;
                }
                files.add(new RepositorySemanticFile(candidate.path(), content));
                retainedBytes += bytes;
            } catch (RuntimeException ex) {
                if (org.springframework.core.NestedExceptionUtils.getMostSpecificCause(ex) instanceof ExternalHttpResponseTooLargeException) {
                    limitations.add(new RepositorySemanticLimitation(candidate.path(), "TOO_LARGE", "semantic_context_byte_budget"));
                    if (properties.getSemanticIndexMaxTotalBytes() - retainedBytes < properties.getSemanticIndexMaxFileBytes()) truncated = true;
                    continue;
                }
                limitations.add(new RepositorySemanticLimitation(
                    candidate.path(), "UNAVAILABLE", deadline.exhausted() ? "semantic_context_deadline_exhausted" : "semantic_context_fetch_failed"
                ));
            }
        }
        return new RepositorySemanticSnapshot(
            branch,
            files,
            limitations,
            truncated,
            "branch=" + safeBranch(branch) + "; commit=" + commitSha + "; deterministic=true; candidates=" + candidates.size(),
            commitSha
        );
    }

    private JsonNode getJson(
        GithubIntegrationSettings settings,
        String baseUrl,
        String owner,
        String repository,
        String branch,
        String operation,
        ReviewDeadline deadline
    ) {
        var builder = UriComponentsBuilder.fromUriString(baseUrl).pathSegment("repos", owner, repository);
        if ("resolve_semantic_commit".equals(operation)) builder.pathSegment("commits", branch);
        else if (!"fetch_repository_metadata".equals(operation)) {
            builder.pathSegment("git", "trees", branch).queryParam("recursive", "1");
        }
        URI uri = builder.build().encode().toUri();
        endpointPolicy.validate(OutboundEndpointType.GITHUB, uri.toString());
        return observe(operation, () -> resilience.github(operation, deadline::remainingNanos,
            () -> GithubRestClientFactory.withinDeadline(restClient, deadline).get().uri(uri)
            .headers(headers -> applyGithubHeaders(headers, settings))
            .exchange((request, response) -> jsonReader.readSuccessfulTree(
                response, "GitHub " + operation + " failed", ExternalHttpResponseProfile.GITHUB
            ))));
    }

    private <T> T observe(String stage, java.util.function.Supplier<T> call) {
        long startedAt = System.nanoTime();
        String outcome = "failed";
        try {
            T result = call.get();
            outcome = "success";
            return result;
        } finally {
            if (meterRegistry != null) meterRegistry.timer("repoguard.review.semantic.phase.duration", "stage", stage,
                "outcome", outcome).record(Duration.ofNanos(System.nanoTime() - startedAt));
        }
    }

    private void applyGithubHeaders(HttpHeaders headers, GithubIntegrationSettings settings) {
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        if (StringUtils.hasText(settings.token())) {
            headers.setBearerAuth(settings.token().trim());
        }
    }

    private List<TreeEntry> treeEntries(JsonNode root) {
        List<TreeEntry> entries = new ArrayList<>();
        JsonNode tree = root == null ? null : root.path("tree");
        if (tree == null || !tree.isArray()) {
            return entries;
        }
        for (JsonNode node : tree) {
            String path = text(node.path("path"));
            if ("blob".equals(node.path("type").asText()) && validPath(path) && textPath(path)) {
                entries.add(new TreeEntry(path));
            }
        }
        return entries;
    }

    private List<Candidate> candidates(List<TreeEntry> entries, PullRequestDiff diff, Set<String> symbols) {
        Set<String> changedPaths = diff.files().stream().map(PullRequestChangedFile::filename)
            .filter(StringUtils::hasText).map(this::normalize).collect(java.util.stream.Collectors.toSet());
        Set<String> directories = changedPaths.stream()
            .map(path -> path.substring(0, Math.max(0, path.lastIndexOf('/'))))
            .filter(StringUtils::hasText).collect(java.util.stream.Collectors.toSet());
        Set<String> extensions = changedPaths.stream().map(this::extension).filter(StringUtils::hasText)
            .collect(java.util.stream.Collectors.toSet());
        Set<String> tokens = tokens(symbols);
        return entries.stream().filter(entry -> !changedPaths.contains(normalize(entry.path())))
            .map(entry -> {
                boolean configuration = isConfig(entry.path());
                int score = score(entry.path(), directories, extensions, tokens) + (configuration ? 2 : 0);
                return new Candidate(entry.path(), score);
            })
            .filter(candidate -> candidate.score() > 0)
            .sorted(Comparator.comparingInt(Candidate::score).reversed().thenComparing(Candidate::path))
            .toList();
    }

    private int score(String path, Set<String> directories, Set<String> extensions, Set<String> tokens) {
        String normalized = normalize(path);
        String basename = normalized.substring(normalized.lastIndexOf('/') + 1);
        int score = directories.stream().anyMatch(normalized::startsWith) ? 12 : 0;
        score += extensions.contains(extension(normalized)) ? 4 : 0;
        for (String token : tokens) {
            if (basename.contains(token)) {
                score += 24;
            } else if (normalized.contains(token)) {
                score += 8;
            }
        }
        return score;
    }

    private Set<String> tokens(Set<String> symbols) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String symbol : symbols) {
            if (StringUtils.hasText(symbol)) {
                tokens.add(symbol.toLowerCase(Locale.ROOT));
                for (String part : symbol.split("(?<=[a-z0-9])(?=[A-Z])")) {
                    if (part.length() >= 3) {
                        tokens.add(part.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        return tokens;
    }

    private boolean validPath(String path) {
        if (!StringUtils.hasText(path)) {
            return false;
        }
        String normalized = path.replace('\\', '/');
        return !normalized.startsWith("/") && !normalized.endsWith("/")
            && java.util.Arrays.stream(normalized.split("/"))
                .noneMatch(segment -> segment.isBlank() || ".".equals(segment) || "..".equals(segment));
    }

    private boolean textPath(String path) {
        String normalized = normalize(path);
        return !filePolicy.excluded(path) && TEXT_EXTENSIONS.stream().anyMatch(normalized::endsWith);
    }

    private boolean isConfig(String path) {
        String normalized = normalize(path);
        return normalized.endsWith(".yml") || normalized.endsWith(".yaml") || normalized.endsWith(".properties")
            || normalized.endsWith(".toml") || normalized.endsWith(".xml") || normalized.endsWith(".json")
            || normalized.endsWith(".conf") || normalized.endsWith(".ini") || normalized.endsWith(".sql");
    }

    private String extension(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot);
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? "" : node.asText("").trim();
    }

    private String safeBranch(String branch) {
        String sanitized = branch.replaceAll("[^A-Za-z0-9._/-]", "_");
        return sanitized.substring(0, Math.min(80, sanitized.length()));
    }

    private RepositorySemanticSnapshot degraded(String reason) {
        return new RepositorySemanticSnapshot(
            "", List.of(), List.of(new RepositorySemanticLimitation("[repository]", "UNAVAILABLE", reason)),
            false, "unavailable; reason=" + reason
        );
    }

    private record TreeEntry(String path) {
    }

    private record Candidate(String path, int score) {
    }

    private static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private record CacheKey(long tenantId, Long integrationId, String credentialRevision, String baseUrl,
        String owner, String repository, String commitSha, List<String> symbols, String changedPaths, List<Integer> policy) {
    }
}
