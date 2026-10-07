package com.repoguard.agent.github;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.repoguard.agent.external.ExternalCallResilience;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/** Reads bounded path metadata only; never loads file context or retains patch text. */
@Component
public class GithubPullRequestPathsReader {
    private final GithubPaginator paginator;
    private final GithubPullRequestHeadReader heads;
    private final LongSupplier clock;
    @Autowired
    public GithubPullRequestPathsReader(GithubPaginator paginator, GithubPullRequestHeadReader heads) { this(paginator, heads, System::nanoTime); }
    GithubPullRequestPathsReader(GithubPaginator paginator, GithubPullRequestHeadReader heads, LongSupplier clock) {
        this.paginator = paginator; this.heads = heads; this.clock = clock;
    }
    public record Change(String filename, String status, @JsonProperty("previous_filename") String previousFilename) { }
    public record Paths(String status, Map<String, Change> files) { }
    public Paths fetch(GithubIntegrationSettings settings, String baseUrl, String owner, String repository,
                       Integer pullNumber, String expectedHead, String expectedBase, ExternalCallResilience resilience) {
        if (expectedHead == null || !expectedHead.matches("[a-fA-F0-9]{40}")
            || expectedBase == null || !expectedBase.matches("[a-fA-F0-9]{40}")) return empty("TASK_CHANGED");
        long start = clock.getAsLong();
        var before = heads.fetchHead(settings, baseUrl, owner, repository, pullNumber, resilience);
        if (!expectedHead.equalsIgnoreCase(before.sha()) || !expectedBase.equalsIgnoreCase(before.baseSha())) return empty("TASK_CHANGED");
        if (clock.getAsLong() - start >= TimeUnit.SECONDS.toNanos(60)) return empty("BUDGET_EXCEEDED");
        Map<String, Change> files = new LinkedHashMap<>(); String[] failure = {null}; int[] retainedBytes = {0};
        var traversal = paginator.traversePages("fetch_codeowners_paths", page -> UriComponentsBuilder.fromUriString(baseUrl)
            .path("/repos/{owner}/{repo}/pulls/{pullNumber}/files").queryParam("per_page", 100).queryParam("page", page)
            .build(owner, repository, pullNumber).toString(), settings, Change[].class, resilience, 3, (items, more) -> {
                if (items.size() > 100 || clock.getAsLong() - start >= TimeUnit.SECONDS.toNanos(60)) {
                    failure[0] = "BUDGET_EXCEEDED"; return false;
                }
                for (Change item : items) {
                    if (item == null || !validPath(item.filename()) || item.status() == null || !List.of("added", "modified", "removed", "renamed", "copied", "changed", "unchanged").contains(item.status())
                        || item.previousFilename() != null && !validPath(item.previousFilename())
                        || "renamed".equals(item.status()) && (!validPath(item.previousFilename()) || item.filename().equals(item.previousFilename()))) {
                        failure[0] = "INVALID_CHANGES"; return false;
                    }
                    if (files.putIfAbsent(item.filename(), item) != null) { failure[0] = "INVALID_CHANGES"; return false; }
                    retainedBytes[0] += item.filename().getBytes(StandardCharsets.UTF_8).length
                        + (item.previousFilename() == null ? 0 : item.previousFilename().getBytes(StandardCharsets.UTF_8).length);
                    if (files.size() > 200 || retainedBytes[0] > 524288) { failure[0] = "BUDGET_EXCEEDED"; return false; }
                }
                return true;
            });
        if (failure[0] != null) return empty(failure[0]);
        if (traversal.pageLimitReached() || traversal.consumerStopped() || clock.getAsLong() - start >= TimeUnit.SECONDS.toNanos(60))
            return empty("BUDGET_EXCEEDED");
        var after = heads.fetchHead(settings, baseUrl, owner, repository, pullNumber, resilience);
        if (!before.equals(after)) return empty("TASK_CHANGED");
        if (clock.getAsLong() - start >= TimeUnit.SECONDS.toNanos(60)) return empty("BUDGET_EXCEEDED");
        return new Paths("COMPLETE", Map.copyOf(files));
    }
    private static boolean validPath(String path) {
        return path != null && !path.isBlank() && path.length() <= 512 && !path.startsWith("/") && !path.endsWith("/")
            && !path.contains("\\") && !path.contains("//") && path.chars().noneMatch(Character::isISOControl)
            && path.split("/").length <= 32 && Arrays.stream(path.split("/")).noneMatch(part -> part.equals(".") || part.equals(".."));
    }
    private static Paths empty(String status) { return new Paths(status, Map.of()); }
}
