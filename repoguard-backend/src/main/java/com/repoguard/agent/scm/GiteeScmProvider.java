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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/** Gitee OpenAPI v5 adapter for pull requests, diffs, comments and commit statuses. */
@Service
public class GiteeScmProvider implements ScmProvider {

    private static final String PROVIDER = "GITEE";
    private static final String DEFAULT_BASE_URL = "https://gitee.com";
    private static final String API_SUFFIX = "/api/v5";
    private static final int MAX_FILES = 1_000;

    private final ScmProviderHttpSupport http;

    @Autowired
    public GiteeScmProvider(
        ScmIntegrationConfigProvider configProvider,
        RestClient.Builder restClientBuilder,
        ExternalHttpJsonResponseReader responseReader,
        ExternalCallResilience resilience,
        OutboundEndpointPolicy endpointPolicy
    ) {
        this.http = new ScmProviderHttpSupport(PROVIDER, DEFAULT_BASE_URL,
            ExternalHttpResponseProfile.GITEE, OutboundEndpointType.GITEE,
            configProvider, restClientBuilder, responseReader, resilience, endpointPolicy);
    }

    @Override
    public String providerKey() {
        return PROVIDER;
    }

    @Override
    public ScmIntegrationSettings settings() {
        return http.settings();
    }

    @Override
    public ScmRepositoryRef configuredRepository() {
        return http.configuredRepository();
    }

    @Override
    public List<ScmChangeRequestSummary> listOpenChangeRequests() {
        ScmIntegrationSettings settings = http.requireSettings();
        ScmRepositoryRef repository = http.repository(settings.defaultNamespace(), settings.defaultRepository(), true);
        JsonNode root = http.get("list_open_pull_requests", pullRequestsUrl(settings, repository), settings);
        if (root == null || !root.isArray()) {
            return List.of();
        }
        List<ScmChangeRequestSummary> result = new ArrayList<>();
        for (JsonNode item : root) {
            int number = integer(item, "number", "id");
            if (number < 1) {
                continue;
            }
            result.add(new ScmChangeRequestSummary(PROVIDER, repository.namespace(), repository.repository(), number,
                text(item, "title"), text(item, "head.ref"), firstText(item, "head.sha", "head.commit.sha"),
                firstText(item, "user.login", "user.name"), firstText(item, "html_url", "url"),
                firstText(item, "updated_at", "updated")));
        }
        return List.copyOf(result);
    }

    @Override
    public PullRequestDiff fetchPullRequestDiff(ReviewTask task) {
        ScmIntegrationSettings settings = http.requireSettings();
        ScmRepositoryRef repository = taskRepository(task, settings);
        int number = requiredNumber(task);
        JsonNode root = http.get("fetch_pull_request_files", filesUrl(settings, repository, number), settings);
        List<PullRequestChangedFile> files = new ArrayList<>();
        if (root != null && root.isArray()) {
            for (JsonNode item : root) {
                if (files.size() >= MAX_FILES) {
                    break;
                }
                String path = firstText(item, "filename", "new_path", "old_path");
                if (!StringUtils.hasText(path)) {
                    continue;
                }
                files.add(new PullRequestChangedFile(path, text(item, "status"),
                    integerOrNull(item, "additions"), integerOrNull(item, "deletions"), text(item, "patch")));
            }
        }
        return new PullRequestDiff(repository.namespace(), repository.repository(), number,
            fetchPullRequestHeadSha(task), files,
            files.size() >= MAX_FILES
                ? new PullRequestDiffTruncation(List.of(PullRequestDiffTruncation.Reason.MAX_FILES), 1, files.size(), 0)
                : PullRequestDiffTruncation.none());
    }

    @Override
    public String fetchPullRequestHeadSha(ReviewTask task) {
        ScmIntegrationSettings settings = http.requireSettings();
        ScmRepositoryRef repository = taskRepository(task, settings);
        JsonNode root = http.get("fetch_pull_request_head", pullRequestUrl(settings, repository, requiredNumber(task)), settings);
        String sha = firstText(root, "head.sha", "head.commit.sha", "sha");
        if (!StringUtils.hasText(sha)) {
            throw new IllegalStateException("Gitee pull request head SHA is unavailable");
        }
        return sha;
    }

    @Override
    public ScmCommentResult publishComment(ReviewTask task, ScmCommentDraft draft) {
        if (draft == null || !StringUtils.hasText(draft.body())) {
            throw new IllegalArgumentException("SCM comment body is required");
        }
        ScmIntegrationSettings settings = http.requireSettings();
        ScmRepositoryRef repository = taskRepository(task, settings);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("body", draft.body().trim());
        if (StringUtils.hasText(draft.path())) {
            body.put("path", draft.path().trim());
        }
        if (draft.line() != null && draft.line() > 0) {
            body.put("position", draft.line());
        }
        JsonNode response = http.post("publish_pull_request_comment",
            commentsUrl(settings, repository, requiredNumber(task)), settings, body);
        return new ScmCommentResult(PROVIDER, draft.findingId(), true, "PUBLISHED", "Gitee pull request comment published",
            firstText(response, "html_url", "url"), longValue(response, "id"));
    }

    @Override
    public ScmStatusResult publishStatus(ReviewTask task, ScmStatusRequest request) {
        ScmIntegrationSettings settings = http.requireSettings();
        ScmRepositoryRef repository = taskRepository(task, settings);
        String state = normalizeState(request == null ? null : request.state());
        String sha = StringUtils.hasText(task.getCommitSha()) ? task.getCommitSha().trim() : fetchPullRequestHeadSha(task);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", state);
        body.put("context", request == null || !StringUtils.hasText(request.name()) ? "RepoGuard PR Review" : request.name().trim());
        if (request != null && StringUtils.hasText(request.description())) {
            body.put("description", request.description().trim());
        }
        if (request != null && StringUtils.hasText(request.targetUrl())) {
            body.put("target_url", request.targetUrl().trim());
        }
        JsonNode response = http.post("publish_commit_status", statusUrl(settings, repository, sha), settings, body);
        return new ScmStatusResult(PROVIDER, true, state, "Gitee commit status updated", firstText(response, "target_url", "url"));
    }

    private ScmRepositoryRef taskRepository(ReviewTask task, ScmIntegrationSettings settings) {
        if (task == null) {
            throw new IllegalArgumentException("Review task is required");
        }
        return http.repository(StringUtils.hasText(task.getOrganization()) ? task.getOrganization() : settings.defaultNamespace(),
            StringUtils.hasText(task.getRepository()) ? task.getRepository() : settings.defaultRepository(), true);
    }

    private int requiredNumber(ReviewTask task) {
        if (task == null || task.getPrNumber() == null || task.getPrNumber() < 1) {
            throw new IllegalArgumentException("Pull request number is required");
        }
        return task.getPrNumber();
    }

    private String pullRequestsUrl(ScmIntegrationSettings settings, ScmRepositoryRef repository) {
        return projectUrl(settings, repository, "/pulls?state=open&page=1&per_page=100");
    }

    private String filesUrl(ScmIntegrationSettings settings, ScmRepositoryRef repository, int number) {
        return projectUrl(settings, repository, "/pulls/" + number + "/files");
    }

    private String pullRequestUrl(ScmIntegrationSettings settings, ScmRepositoryRef repository, int number) {
        return projectUrl(settings, repository, "/pulls/" + number);
    }

    private String commentsUrl(ScmIntegrationSettings settings, ScmRepositoryRef repository, int number) {
        return projectUrl(settings, repository, "/pulls/" + number + "/comments");
    }

    private String statusUrl(ScmIntegrationSettings settings, ScmRepositoryRef repository, String sha) {
        return projectUrl(settings, repository, "/statuses/" + sha);
    }

    private String projectUrl(ScmIntegrationSettings settings, ScmRepositoryRef repository, String suffix) {
        return http.pathUrl(settings, API_SUFFIX, "repos", repository.namespace(), repository.repository()) + suffix;
    }

    private int integer(JsonNode node, String... fields) {
        Integer value = integerOrNull(node, fields);
        return value == null ? -1 : value;
    }

    private Integer integerOrNull(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node;
            for (String part : field.split("\\.")) {
                value = value == null ? null : value.path(part);
            }
            if (value != null && value.canConvertToInt()) {
                return value.asInt();
            }
        }
        return null;
    }

    private Long longValue(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        return value != null && value.canConvertToLong() ? value.asLong() : null;
    }

    private String text(JsonNode node, String field) {
        return firstText(node, field);
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node;
            for (String part : field.split("\\.")) {
                value = value == null ? null : value.path(part);
            }
            if (value != null && StringUtils.hasText(value.asText(null))) {
                return value.asText().trim();
            }
        }
        return null;
    }

    private String normalizeState(String state) {
        String value = state == null ? "pending" : state.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "pending", "running", "success", "failed", "canceled" -> value;
            case "failure" -> "failed";
            case "cancelled" -> "canceled";
            default -> throw new IllegalArgumentException("Unsupported Gitee commit status: " + state);
        };
    }
}
