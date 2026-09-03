package com.repoguard.agent.scm;

import com.fasterxml.jackson.databind.JsonNode;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.external.ExternalHttpJsonResponseReader;
import com.repoguard.agent.external.ExternalHttpResponseProfile;
import com.repoguard.agent.external.OutboundEndpointPolicy;
import com.repoguard.agent.external.OutboundEndpointType;
import com.repoguard.agent.review.PullRequestChangedFile;
import com.repoguard.agent.review.PullRequestDiff;
import com.repoguard.agent.review.PullRequestDiffTruncation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** GitLab Merge Requests API adapter. The project path is encoded as one API path segment. */
@Service
public class GitlabScmProvider implements ScmProvider {

    private static final String PROVIDER = "GITLAB";
    private static final String DEFAULT_BASE_URL = "https://gitlab.com";
    private static final int MAX_FILES = 1_000;
    private static final int MAX_PATCH_BYTES = 512 * 1024;

    private final ScmIntegrationConfigProvider configProvider;
    private final RestClient restClient;
    private final ExternalHttpJsonResponseReader jsonResponseReader;
    private final ExternalCallResilience resilience;
    private final OutboundEndpointPolicy endpointPolicy;

    @Autowired
    public GitlabScmProvider(
        ScmIntegrationConfigProvider configProvider,
        RestClient.Builder restClientBuilder,
        ExternalHttpJsonResponseReader jsonResponseReader,
        ExternalCallResilience resilience,
        OutboundEndpointPolicy endpointPolicy
    ) {
        this(configProvider, restClientBuilder, jsonResponseReader, resilience, endpointPolicy, true);
    }

    GitlabScmProvider(
        ScmIntegrationConfigProvider configProvider,
        RestClient.Builder restClientBuilder,
        ExternalHttpJsonResponseReader jsonResponseReader,
        ExternalCallResilience resilience,
        OutboundEndpointPolicy endpointPolicy,
        boolean ignored
    ) {
        this.configProvider = Objects.requireNonNull(configProvider, "configProvider");
        this.restClient = Objects.requireNonNull(restClientBuilder, "restClientBuilder").build();
        this.jsonResponseReader = Objects.requireNonNull(jsonResponseReader, "jsonResponseReader");
        this.resilience = Objects.requireNonNull(resilience, "resilience");
        this.endpointPolicy = endpointPolicy;
    }

    @Override
    public String providerKey() {
        return PROVIDER;
    }

    @Override
    public ScmIntegrationSettings settings() {
        return configProvider.settings(PROVIDER);
    }

    @Override
    public ScmRepositoryRef configuredRepository() {
        ScmIntegrationSettings settings = settings();
        if (settings == null || !StringUtils.hasText(settings.defaultNamespace())
            || !StringUtils.hasText(settings.defaultRepository())) {
            return null;
        }
        return new ScmRepositoryRef(settings.defaultNamespace(), settings.defaultRepository());
    }

    @Override
    public List<ScmChangeRequestSummary> listOpenChangeRequests() {
        ScmIntegrationSettings settings = requireConfiguredSettings();
        ScmRepositoryRef repository = requireRepository(settings.defaultNamespace(), settings.defaultRepository());
        JsonNode root = get("list_open_merge_requests", mergeRequestsUrl(settings, repository));
        if (root == null || !root.isArray()) {
            return List.of();
        }
        List<ScmChangeRequestSummary> result = new ArrayList<>();
        for (JsonNode item : root) {
            Integer number = integer(item, "iid", "id");
            if (number == null || number < 1) {
                continue;
            }
            result.add(new ScmChangeRequestSummary(
                PROVIDER,
                repository.namespace(),
                repository.repository(),
                number,
                text(item, "title"),
                text(item, "source_branch"),
                firstText(item, "sha", "diff_refs.head_sha"),
                text(item.path("author"), "username"),
                text(item, "web_url"),
                text(item, "updated_at")
            ));
        }
        return List.copyOf(result);
    }

    @Override
    public PullRequestDiff fetchPullRequestDiff(ReviewTask task) {
        ScmIntegrationSettings settings = requireConfiguredSettings();
        ScmRepositoryRef repository = taskRepository(task, settings);
        int iid = requiredNumber(task);
        JsonNode root = get("fetch_merge_request_diff", changesUrl(settings, repository, iid));
        JsonNode changes = root == null ? null : root.path("changes");
        List<PullRequestChangedFile> files = new ArrayList<>();
        boolean truncated = false;
        PullRequestDiffTruncation.Reason truncationReason = null;
        long retainedBytes = 0L;
        if (changes != null && changes.isArray()) {
            for (JsonNode change : changes) {
                String patch = text(change, "diff");
                if (patch == null) {
                    patch = "";
                }
                int patchBytes = patch.getBytes(StandardCharsets.UTF_8).length;
                if (files.size() >= MAX_FILES) {
                    truncated = true;
                    truncationReason = PullRequestDiffTruncation.Reason.MAX_FILES;
                    break;
                }
                if (retainedBytes + Math.min(patchBytes, MAX_PATCH_BYTES) > MAX_FILES * (long) MAX_PATCH_BYTES) {
                    truncated = true;
                    truncationReason = PullRequestDiffTruncation.Reason.MAX_TOTAL_BYTES;
                    break;
                }
                String path = firstText(change, "new_path", "old_path");
                if (!StringUtils.hasText(path)) {
                    continue;
                }
                if (patchBytes > MAX_PATCH_BYTES) {
                    patch = new String(Arrays.copyOf(patch.getBytes(StandardCharsets.UTF_8), MAX_PATCH_BYTES), StandardCharsets.UTF_8);
                    truncated = true;
                    truncationReason = PullRequestDiffTruncation.Reason.MAX_PATCH_BYTES;
                }
                retainedBytes += patch.getBytes(StandardCharsets.UTF_8).length;
                files.add(new PullRequestChangedFile(
                    path,
                    status(change),
                    countLines(patch, '+'),
                    countLines(patch, '-'),
                    patch
                ));
            }
        }
        PullRequestDiffTruncation truncation = truncated
            ? new PullRequestDiffTruncation(List.of(
                truncationReason == null ? PullRequestDiffTruncation.Reason.MAX_PATCH_BYTES : truncationReason
            ), 1, files.size(), retainedBytes)
            : PullRequestDiffTruncation.none();
        return new PullRequestDiff(repository.namespace(), repository.repository(), iid,
            fetchPullRequestHeadSha(task), files, truncation);
    }

    @Override
    public String fetchPullRequestHeadSha(ReviewTask task) {
        ScmIntegrationSettings settings = requireConfiguredSettings();
        ScmRepositoryRef repository = taskRepository(task, settings);
        JsonNode root = get("fetch_merge_request_head", mergeRequestUrl(settings, repository, requiredNumber(task)));
        String sha = firstText(root, "sha", "diff_refs.head_sha");
        if (!StringUtils.hasText(sha)) {
            throw new IllegalStateException("GitLab merge request head SHA is unavailable");
        }
        return sha.trim();
    }

    @Override
    public ScmCommentResult publishComment(ReviewTask task, ScmCommentDraft draft) {
        if (draft == null || !StringUtils.hasText(draft.body())) {
            throw new IllegalArgumentException("SCM comment body is required");
        }
        ScmIntegrationSettings settings = requireConfiguredSettings();
        ScmRepositoryRef repository = taskRepository(task, settings);
        Map<String, Object> body = Map.of("body", draft.body().trim());
        JsonNode response = post("publish_merge_request_comment", notesUrl(settings, repository, requiredNumber(task)), body);
        return new ScmCommentResult(
            PROVIDER,
            draft.findingId(),
            true,
            "PUBLISHED",
            "GitLab merge request note published",
            text(response, "web_url"),
            longValue(response, "id")
        );
    }

    @Override
    public ScmStatusResult publishStatus(ReviewTask task, ScmStatusRequest request) {
        ScmIntegrationSettings settings = requireConfiguredSettings();
        ScmRepositoryRef repository = taskRepository(task, settings);
        String sha = StringUtils.hasText(task.getCommitSha()) ? task.getCommitSha().trim() : fetchPullRequestHeadSha(task);
        String state = normalizeState(request == null ? null : request.state());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", state);
        body.put("name", request == null || !StringUtils.hasText(request.name()) ? "RepoGuard PR Review" : request.name().trim());
        if (request != null && StringUtils.hasText(request.description())) {
            body.put("description", request.description().trim());
        }
        if (request != null && StringUtils.hasText(request.targetUrl())) {
            body.put("target_url", request.targetUrl().trim());
        }
        JsonNode response = post("publish_commit_status", statusUrl(settings, repository, sha), body);
        return new ScmStatusResult(PROVIDER, true, state, "GitLab commit status updated", text(response, "target_url"));
    }

    private ScmIntegrationSettings requireConfiguredSettings() {
        ScmIntegrationSettings settings = settings();
        if (settings == null || !StringUtils.hasText(settings.token())) {
            throw new IllegalStateException("GitLab token is not configured");
        }
        return settings;
    }

    private ScmRepositoryRef taskRepository(ReviewTask task, ScmIntegrationSettings settings) {
        if (task == null) {
            throw new IllegalArgumentException("Review task is required");
        }
        String namespace = StringUtils.hasText(task.getOrganization()) ? task.getOrganization() : settings.defaultNamespace();
        String repository = StringUtils.hasText(task.getRepository()) ? task.getRepository() : settings.defaultRepository();
        return requireRepository(namespace, repository);
    }

    private ScmRepositoryRef requireRepository(String namespace, String repository) {
        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(repository)) {
            throw new IllegalStateException("GitLab namespace or repository is not configured");
        }
        return new ScmRepositoryRef(namespace.trim(), repository.trim());
    }

    private int requiredNumber(ReviewTask task) {
        if (task == null || task.getPrNumber() == null || task.getPrNumber() < 1) {
            throw new IllegalArgumentException("Merge request number is required");
        }
        return task.getPrNumber();
    }

    private JsonNode get(String operation, String url) {
        ScmIntegrationSettings settings = requireConfiguredSettings();
        return resilience.github("gitlab_" + operation, () -> restClient.get()
            .uri(validatedUrl(url))
            .headers(headers -> applyHeaders(headers, settings))
            .exchange((request, response) -> read(response, "GitLab " + operation + " failed")));
    }

    private JsonNode post(String operation, String url, Object body) {
        ScmIntegrationSettings settings = requireConfiguredSettings();
        return resilience.github("gitlab_" + operation, () -> restClient.post()
            .uri(validatedUrl(url))
            .headers(headers -> applyHeaders(headers, settings))
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .exchange((request, response) -> read(response, "GitLab " + operation + " failed")));
    }

    private JsonNode read(org.springframework.http.client.ClientHttpResponse response, String failurePrefix) throws IOException {
        return jsonResponseReader.readSuccessfulTree(response, failurePrefix, ExternalHttpResponseProfile.GITLAB);
    }

    private void applyHeaders(HttpHeaders headers, ScmIntegrationSettings settings) {
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("PRIVATE-TOKEN", settings.token().trim());
    }

    private String validatedUrl(String url) {
        if (endpointPolicy != null) {
            endpointPolicy.validate(OutboundEndpointType.GITLAB, url);
        }
        return url;
    }

    private String apiBase(ScmIntegrationSettings settings) {
        String base = StringUtils.hasText(settings.baseUrl()) ? settings.baseUrl().trim() : DEFAULT_BASE_URL;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.endsWith("/api/v4") ? base : base + "/api/v4";
    }

    private String mergeRequestsUrl(ScmIntegrationSettings settings, ScmRepositoryRef repository) {
        return projectUrl(settings, repository, "/merge_requests") + "?state=opened&order_by=updated_at&sort=desc&per_page=100";
    }

    private String changesUrl(ScmIntegrationSettings settings, ScmRepositoryRef repository, int iid) {
        return projectUrl(settings, repository, "/merge_requests/" + iid + "/changes");
    }

    private String mergeRequestUrl(ScmIntegrationSettings settings, ScmRepositoryRef repository, int iid) {
        return projectUrl(settings, repository, "/merge_requests/" + iid);
    }

    private String notesUrl(ScmIntegrationSettings settings, ScmRepositoryRef repository, int iid) {
        return projectUrl(settings, repository, "/merge_requests/" + iid + "/notes");
    }

    private String statusUrl(ScmIntegrationSettings settings, ScmRepositoryRef repository, String sha) {
        return projectUrl(settings, repository, "/statuses/" + sha);
    }

    private String projectUrl(ScmIntegrationSettings settings, ScmRepositoryRef repository, String suffix) {
        return UriComponentsBuilder.fromUriString(apiBase(settings))
            .path("/projects/")
            .pathSegment(repository.fullName())
            .path(suffix)
            .build()
            .encode()
            .toUriString();
    }

    private String status(JsonNode change) {
        if (change.path("deleted_file").asBoolean(false)) {
            return "removed";
        }
        if (change.path("new_file").asBoolean(false)) {
            return "added";
        }
        if (change.path("renamed_file").asBoolean(false)) {
            return "renamed";
        }
        return "modified";
    }

    private int countLines(String patch, char marker) {
        if (!StringUtils.hasText(patch)) {
            return 0;
        }
        return (int) patch.lines()
            .filter(line -> line.length() > 1 && line.charAt(0) == marker
                && !line.startsWith("+++" ) && !line.startsWith("---"))
            .count();
    }

    private Integer integer(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.canConvertToInt()) {
                return value.asInt();
            }
        }
        return null;
    }

    private Long longValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value != null && value.canConvertToLong() ? value.asLong() : null;
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode current = node;
            for (String part : field.split("\\.")) {
                current = current == null ? null : current.path(part);
            }
            if (current != null && StringUtils.hasText(current.asText(null))) {
                return current.asText().trim();
            }
        }
        return null;
    }

    private String text(JsonNode node, String field) {
        return firstText(node, field);
    }

    private String normalizeState(String state) {
        String value = state == null ? "pending" : state.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "pending", "running", "success", "failed", "canceled" -> value;
            case "failure" -> "failed";
            case "cancelled" -> "canceled";
            default -> throw new IllegalArgumentException("Unsupported GitLab commit status: " + state);
        };
    }
}
