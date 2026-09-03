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

/** Bitbucket Cloud v2 adapter for pull requests, diffstat, comments and build statuses. */
@Service
public class BitbucketScmProvider implements ScmProvider {

    private static final String PROVIDER = "BITBUCKET";
    private static final String DEFAULT_BASE_URL = "https://api.bitbucket.org";
    private static final String API_SUFFIX = "/2.0";
    private static final int MAX_FILES = 1_000;

    private final ScmProviderHttpSupport http;

    @Autowired
    public BitbucketScmProvider(
        ScmIntegrationConfigProvider configProvider,
        RestClient.Builder restClientBuilder,
        ExternalHttpJsonResponseReader responseReader,
        ExternalCallResilience resilience,
        OutboundEndpointPolicy endpointPolicy
    ) {
        this.http = new ScmProviderHttpSupport(PROVIDER, DEFAULT_BASE_URL,
            ExternalHttpResponseProfile.BITBUCKET, OutboundEndpointType.BITBUCKET,
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
        JsonNode values = root != null && root.isObject() ? root.path("values") : root;
        if (values == null || !values.isArray()) {
            return List.of();
        }
        List<ScmChangeRequestSummary> result = new ArrayList<>();
        for (JsonNode item : values) {
            int number = integer(item, "id");
            if (number < 1) {
                continue;
            }
            result.add(new ScmChangeRequestSummary(PROVIDER, repository.namespace(), repository.repository(), number,
                text(item, "title"), text(item, "source.branch.name"),
                firstText(item, "source.commit.hash", "source.commit.links.html.href"),
                firstText(item, "author.display_name", "author.nickname", "author.username"),
                text(item, "links.html.href"), text(item, "updated_on")));
        }
        return List.copyOf(result);
    }

    @Override
    public PullRequestDiff fetchPullRequestDiff(ReviewTask task) {
        ScmIntegrationSettings settings = http.requireSettings();
        ScmRepositoryRef repository = taskRepository(task, settings);
        int number = requiredNumber(task);
        JsonNode root = http.get("fetch_pull_request_diffstat", diffstatUrl(settings, repository, number), settings);
        JsonNode values = root != null && root.isObject() ? root.path("values") : root;
        List<PullRequestChangedFile> files = new ArrayList<>();
        if (values != null && values.isArray()) {
            for (JsonNode item : values) {
                if (files.size() >= MAX_FILES) {
                    break;
                }
                String path = firstText(item, "new.path", "old.path");
                if (!StringUtils.hasText(path)) {
                    continue;
                }
                files.add(new PullRequestChangedFile(path, status(item),
                    integerOrNull(item, "lines_added"), integerOrNull(item, "lines_removed"), null));
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
        String sha = firstText(root, "source.commit.hash");
        if (!StringUtils.hasText(sha)) {
            throw new IllegalStateException("Bitbucket pull request head SHA is unavailable");
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
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("raw", draft.body().trim());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);
        if (StringUtils.hasText(draft.path()) && draft.line() != null && draft.line() > 0) {
            body.put("inline", Map.of("to", draft.line(), "path", draft.path().trim()));
        }
        JsonNode response = http.post("publish_pull_request_comment",
            commentsUrl(settings, repository, requiredNumber(task)), settings, body);
        return new ScmCommentResult(PROVIDER, draft.findingId(), true, "PUBLISHED", "Bitbucket pull request comment published",
            firstText(response, "links.html.href"), longValue(response, "id"));
    }

    @Override
    public ScmStatusResult publishStatus(ReviewTask task, ScmStatusRequest request) {
        ScmIntegrationSettings settings = http.requireSettings();
        ScmRepositoryRef repository = taskRepository(task, settings);
        String state = normalizeState(request == null ? null : request.state());
        String sha = StringUtils.hasText(task.getCommitSha()) ? task.getCommitSha().trim() : fetchPullRequestHeadSha(task);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", bitbucketState(state));
        body.put("key", "repoguard-" + sha.substring(0, Math.min(12, sha.length())));
        body.put("name", request == null || !StringUtils.hasText(request.name()) ? "RepoGuard PR Review" : request.name().trim());
        if (request != null && StringUtils.hasText(request.description())) {
            body.put("description", request.description().trim());
        }
        if (request != null && StringUtils.hasText(request.targetUrl())) {
            body.put("url", request.targetUrl().trim());
        }
        JsonNode response = http.post("publish_commit_status", statusUrl(settings, repository, sha), settings, body);
        return new ScmStatusResult(PROVIDER, true, state, "Bitbucket build status updated",
            firstText(response, "url", "links.html.href"));
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
        return projectUrl(settings, repository, "/pullrequests?state=OPEN&pagelen=100");
    }

    private String diffstatUrl(ScmIntegrationSettings settings, ScmRepositoryRef repository, int number) {
        return projectUrl(settings, repository, "/pullrequests/" + number + "/diffstat");
    }

    private String pullRequestUrl(ScmIntegrationSettings settings, ScmRepositoryRef repository, int number) {
        return projectUrl(settings, repository, "/pullrequests/" + number);
    }

    private String commentsUrl(ScmIntegrationSettings settings, ScmRepositoryRef repository, int number) {
        return projectUrl(settings, repository, "/pullrequests/" + number + "/comments");
    }

    private String statusUrl(ScmIntegrationSettings settings, ScmRepositoryRef repository, String sha) {
        return projectUrl(settings, repository, "/commit/" + sha + "/statuses/build");
    }

    private String projectUrl(ScmIntegrationSettings settings, ScmRepositoryRef repository, String suffix) {
        return http.pathUrl(settings, API_SUFFIX, "repositories", repository.namespace(), repository.repository()) + suffix;
    }

    private String status(JsonNode item) {
        JsonNode newPath = item.path("new");
        JsonNode oldPath = item.path("old");
        if (newPath.isMissingNode() || newPath.isNull()) {
            return "removed";
        }
        if (oldPath.isMissingNode() || oldPath.isNull()) {
            return "added";
        }
        String newValue = text(newPath, "path");
        String oldValue = text(oldPath, "path");
        return StringUtils.hasText(newValue) && !newValue.equals(oldValue) ? "renamed" : "modified";
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
            default -> throw new IllegalArgumentException("Unsupported Bitbucket build status: " + state);
        };
    }

    private String bitbucketState(String state) {
        return switch (state) {
            case "pending", "running" -> "INPROGRESS";
            case "success" -> "SUCCESSFUL";
            case "failed" -> "FAILED";
            case "canceled" -> "STOPPED";
            default -> throw new IllegalArgumentException("Unsupported Bitbucket build status: " + state);
        };
    }
}
