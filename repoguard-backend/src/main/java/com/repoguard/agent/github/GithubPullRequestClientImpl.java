package com.repoguard.agent.github;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.repoguard.agent.config.CacheNames;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalCallErrorClassifier;
import com.repoguard.agent.external.ExternalCallException;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.security.SecretCryptoService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class GithubPullRequestClientImpl implements GithubPullRequestClient {

    private static final String GITHUB_PROVIDER = "GITHUB";

    private final IntegrationConfigMapper integrationConfigMapper;
    private final RestClient restClient;
    private final SecretCryptoService secretCryptoService;
    private final RepoGuardMetrics metrics;
    private final ExternalCallResilience resilience;

    GithubPullRequestClientImpl(
        IntegrationConfigMapper integrationConfigMapper,
        RestClient.Builder restClientBuilder,
        SecretCryptoService secretCryptoService
    ) {
        this(integrationConfigMapper, restClientBuilder, secretCryptoService, null, null);
    }

    @Autowired
    public GithubPullRequestClientImpl(
        IntegrationConfigMapper integrationConfigMapper,
        RestClient.Builder restClientBuilder,
        SecretCryptoService secretCryptoService,
        RepoGuardMetrics metrics,
        ExternalCallResilience resilience
    ) {
        this.integrationConfigMapper = integrationConfigMapper;
        this.restClient = restClientBuilder.build();
        this.secretCryptoService = secretCryptoService;
        this.metrics = metrics;
        this.resilience = resilience;
    }

    @Override
    public GithubRepositoryRef getConfiguredRepository() {
        IntegrationConfig config = loadGithubConfig();
        String owner = config == null ? null : config.getDefaultOwner();
        String repository = config == null ? null : config.getDefaultRepo();
        return new GithubRepositoryRef(
            StringUtils.hasText(owner) ? owner.trim() : null,
            StringUtils.hasText(repository) ? repository.trim() : null
        );
    }

    @Override
    @Cacheable(cacheNames = CacheNames.GITHUB_OPEN_PULL_REQUESTS)
    public List<GithubPullRequestSummary> listOpenPullRequests() {
        LocalDateTime startedAt = LocalDateTime.now();
        IntegrationConfig config = loadGithubConfig();
        GithubRepositoryRef repositoryRef = getConfiguredRepository();
        String owner = repositoryRef.owner();
        String repository = repositoryRef.repository();
        if (!StringUtils.hasText(owner) || !StringUtils.hasText(repository)) {
            throw new IllegalStateException("GitHub owner or repository is not configured");
        }
        String baseUrl = config != null && StringUtils.hasText(config.getBaseUrl())
            ? config.getBaseUrl().trim()
            : "https://api.github.com";
        String url = UriComponentsBuilder
            .fromUriString(baseUrl)
            .path("/repos/{owner}/{repo}/pulls")
            .queryParam("state", "open")
            .queryParam("sort", "updated")
            .queryParam("direction", "desc")
            .queryParam("per_page", 50)
            .build(owner.trim(), repository.trim())
            .toString();

        try {
            GithubPullRequestListItem[] items = executeGithub("list_open_pull_requests", () -> restClient.get()
                .uri(url)
                .headers(headers -> applyGithubHeaders(headers, config))
                .retrieve()
                .body(GithubPullRequestListItem[].class));
            recordGithubApiRequest(startedAt, "list_open_pull_requests", "success", null, null);
            markGithubChecked(config, null);
            return items == null ? List.of() : Arrays.stream(items)
                .map(item -> new GithubPullRequestSummary(
                    owner.trim(),
                    repository.trim(),
                    item.number(),
                    item.title(),
                    item.head() == null ? null : item.head().ref(),
                    item.head() == null ? null : item.head().sha(),
                    item.user() == null ? null : item.user().login(),
                    item.htmlUrl(),
                    item.updatedAt()
                ))
                .toList();
        } catch (RuntimeException ex) {
            RuntimeException classified = ExternalCallErrorClassifier.github(ex);
            recordGithubApiRequest(startedAt, "list_open_pull_requests", "failed", classified);
            recordExternalFailure(classified);
            markGithubChecked(config, conciseError(classified));
            throw classified;
        }
    }

    @Override
    public GithubPullRequestDiff fetchPullRequestDiff(ReviewTask task) {
        LocalDateTime startedAt = LocalDateTime.now();
        IntegrationConfig config = loadGithubConfig();
        String owner = choose(task.getOrganization(), config == null ? null : config.getDefaultOwner());
        String repository = choose(task.getRepository(), config == null ? null : config.getDefaultRepo());
        if (!StringUtils.hasText(owner) || !StringUtils.hasText(repository)) {
            throw new IllegalStateException("GitHub owner or repository is not configured");
        }

        String baseUrl = config != null && StringUtils.hasText(config.getBaseUrl())
            ? config.getBaseUrl().trim()
            : "https://api.github.com";
        String url = UriComponentsBuilder
            .fromUriString(baseUrl)
            .path("/repos/{owner}/{repo}/pulls/{pullNumber}/files")
            .build(owner, repository, task.getPrNumber())
            .toString();

        try {
            GithubChangedFile[] files = executeGithub("fetch_pull_request_diff", () -> restClient.get()
                .uri(url)
                .headers(headers -> applyGithubHeaders(headers, config))
                .retrieve()
                .body(GithubChangedFile[].class));

            markGithubChecked(config, null);
            recordGithubApiRequest(startedAt, "fetch_pull_request_diff", "success", null, null);
            List<GithubChangedFile> changedFiles = files == null ? List.of() : Arrays.asList(files);
            return new GithubPullRequestDiff(owner, repository, task.getPrNumber(), changedFiles);
        } catch (RuntimeException ex) {
            RuntimeException classified = ExternalCallErrorClassifier.github(ex);
            recordGithubApiRequest(startedAt, "fetch_pull_request_diff", "failed", classified);
            recordExternalFailure(classified);
            markGithubChecked(config, conciseError(classified));
            throw classified;
        }
    }

    @Override
    public List<GithubReviewCommentResult> publishPullRequestComments(ReviewTask task, List<GithubReviewCommentDraft> drafts) {
        LocalDateTime startedAt = LocalDateTime.now();
        IntegrationConfig config = loadGithubConfig();
        String owner = choose(task.getOrganization(), config == null ? null : config.getDefaultOwner());
        String repository = choose(task.getRepository(), config == null ? null : config.getDefaultRepo());
        if (!StringUtils.hasText(owner) || !StringUtils.hasText(repository)) {
            throw new IllegalStateException("GitHub owner or repository is not configured");
        }
        String token = config == null ? null : secretCryptoService.decrypt(config.getTokenValue());
        if (!StringUtils.hasText(token)) {
            throw new IllegalStateException("GitHub token is not configured");
        }

        String baseUrl = config != null && StringUtils.hasText(config.getBaseUrl())
            ? config.getBaseUrl().trim()
            : "https://api.github.com";
        String lineCommentUrl = UriComponentsBuilder
            .fromUriString(baseUrl)
            .path("/repos/{owner}/{repo}/pulls/{pullNumber}/comments")
            .build(owner, repository, task.getPrNumber())
            .toString();
        String prCommentUrl = UriComponentsBuilder
            .fromUriString(baseUrl)
            .path("/repos/{owner}/{repo}/issues/{pullNumber}/comments")
            .build(owner, repository, task.getPrNumber())
            .toString();

        String commitSha = null;

        List<GithubReviewCommentResult> results = new ArrayList<>();
        int failedCount = 0;
        for (GithubReviewCommentDraft draft : drafts) {
            try {
                GithubReviewCommentResponse response;
                String actualTargetType = draft.targetType();
                if ("pull_request".equals(draft.targetType())) {
                    response = publishPullRequestComment(prCommentUrl, draft.body(), config);
                } else {
                    if (!StringUtils.hasText(commitSha)) {
                        commitSha = resolvePullRequestHeadSha(baseUrl, owner, repository, task, config);
                    }
                    try {
                        response = publishLineComment(lineCommentUrl, draft, commitSha, config);
                    } catch (RuntimeException ex) {
                        if (!isUnresolvableLineComment(ex)) {
                            throw ex;
                        }
                        actualTargetType = "pull_request";
                        response = publishPullRequestComment(prCommentUrl, draft.body(), config);
                    }
                }
                boolean downgradedToPrComment = "pull_request".equals(actualTargetType) && !"pull_request".equals(draft.targetType());
                results.add(new GithubReviewCommentResult(
                    draft.findingId(),
                    draft.path(),
                    draft.line(),
                    actualTargetType,
                    true,
                    downgradedToPrComment ? "downgraded_to_pr_comment" : "published",
                    downgradedToPrComment
                        ? "GitHub line comment could not be resolved; published as PR comment"
                        : "GitHub comment published",
                    response == null ? null : response.htmlUrl(),
                    response == null ? null : response.id()
                ));
                markGithubChecked(config, null);
            } catch (RuntimeException ex) {
                RuntimeException classified = ExternalCallErrorClassifier.github(ex);
                failedCount++;
                recordGithubApiRequest(startedAt, "publish_pull_request_comments", "failed", classified);
                recordExternalFailure(classified);
                String message = conciseError(classified);
                results.add(new GithubReviewCommentResult(
                    draft.findingId(),
                    draft.path(),
                    draft.line(),
                    draft.targetType(),
                    false,
                    "failed",
                    message,
                    null,
                    null
                ));
                markGithubChecked(config, message);
            }
        }
        recordGithubApiRequest(
            startedAt,
            "publish_pull_request_comments",
            failedCount > 0 ? "partial" : "success",
            null,
            null
        );
        return results;
    }

    private void recordExternalFailure(RuntimeException ex) {
        if (metrics != null && ex instanceof ExternalCallException externalCallException) {
            metrics.externalCallFailed(externalCallException);
        }
    }

    private void recordGithubApiRequest(
        LocalDateTime startedAt,
        String operation,
        String result,
        RuntimeException ex
    ) {
        if (ex instanceof ExternalCallException externalCallException) {
            recordGithubApiRequest(
                startedAt,
                operation,
                result,
                externalCallException.getCategory(),
                externalCallException.getStatusCode() == null ? null : externalCallException.getStatusCode().toString()
            );
            return;
        }
        recordGithubApiRequest(startedAt, operation, result, null, null);
    }

    private void recordGithubApiRequest(
        LocalDateTime startedAt,
        String operation,
        String result,
        String category,
        String status
    ) {
        if (metrics != null) {
            metrics.githubApiRequest(Duration.between(startedAt, LocalDateTime.now()), operation, result, category, status);
        }
    }

    private GithubReviewCommentResponse publishPullRequestComment(
        String prCommentUrl,
        String body,
        IntegrationConfig config
    ) {
        return executeGithub("publish_pull_request_comment", () -> restClient.post()
            .uri(prCommentUrl)
            .headers(headers -> applyGithubHeaders(headers, config))
            .body(Map.of("body", body))
            .retrieve()
            .body(GithubReviewCommentResponse.class));
    }

    private GithubReviewCommentResponse publishLineComment(
        String lineCommentUrl,
        GithubReviewCommentDraft draft,
        String commitSha,
        IntegrationConfig config
    ) {
        return executeGithub("publish_line_comment", () -> restClient.post()
            .uri(lineCommentUrl)
            .headers(headers -> applyGithubHeaders(headers, config))
            .body(Map.of(
                "body", draft.body(),
                "commit_id", commitSha,
                "path", draft.path(),
                "line", draft.line(),
                "side", "RIGHT"
            ))
            .retrieve()
            .body(GithubReviewCommentResponse.class));
    }

    private boolean isUnresolvableLineComment(RuntimeException ex) {
        String message = ex.getMessage();
        return StringUtils.hasText(message)
            && message.contains("422")
            && message.contains("could not be resolved");
    }

    private String resolvePullRequestHeadSha(
        String baseUrl,
        String owner,
        String repository,
        ReviewTask task,
        IntegrationConfig config
    ) {
        if (StringUtils.hasText(task.getCommitSha()) && task.getCommitSha().trim().matches("[a-fA-F0-9]{40}")) {
            return task.getCommitSha().trim();
        }
        String url = UriComponentsBuilder
            .fromUriString(baseUrl)
            .path("/repos/{owner}/{repo}/pulls/{pullNumber}")
            .build(owner, repository, task.getPrNumber())
            .toString();
        GithubPullRequestResponse response = executeGithub("resolve_pull_request_head", () -> restClient.get()
            .uri(url)
            .headers(headers -> applyGithubHeaders(headers, config))
            .retrieve()
            .body(GithubPullRequestResponse.class));
        String sha = response == null || response.head() == null ? null : response.head().sha();
        if (!StringUtils.hasText(sha)) {
            throw new IllegalStateException("GitHub pull request head SHA is unavailable");
        }
        return sha.trim();
    }

    private void applyGithubHeaders(HttpHeaders headers, IntegrationConfig config) {
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        String token = config == null ? null : secretCryptoService.decrypt(config.getTokenValue());
        if (StringUtils.hasText(token)) {
            headers.setBearerAuth(token.trim());
        }
    }

    private <T> T executeGithub(String operation, java.util.function.Supplier<T> supplier) {
        return resilience == null ? supplier.get() : resilience.github(operation, supplier);
    }

    private IntegrationConfig loadGithubConfig() {
        return integrationConfigMapper.selectOne(
            new LambdaQueryWrapper<IntegrationConfig>().eq(IntegrationConfig::getProvider, GITHUB_PROVIDER)
        );
    }

    private void markGithubChecked(IntegrationConfig config, String error) {
        if (config == null || config.getId() == null) {
            return;
        }
        config.setLastCheckedAt(LocalDateTime.now());
        config.setLastError(error);
        config.setStatus(error == null ? "CONFIGURED" : "FAILED");
        config.setUpdatedAt(LocalDateTime.now());
        integrationConfigMapper.updateById(config);
    }

    private String choose(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary.trim() : fallback;
    }

    private String conciseError(RuntimeException ex) {
        String message = ex.getMessage();
        if (!StringUtils.hasText(message)) {
            return ex.getClass().getSimpleName();
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private record GithubReviewCommentResponse(
        Long id,
        @JsonProperty("html_url")
        String htmlUrl
    ) {
    }

    private record GithubPullRequestResponse(
        GithubPullRequestHead head
    ) {
    }

    private record GithubPullRequestHead(
        String ref,
        String sha
    ) {
    }

    private record GithubPullRequestListItem(
        Integer number,
        String title,
        GithubPullRequestHead head,
        GithubUser user,
        @JsonProperty("html_url")
        String htmlUrl,
        @JsonProperty("updated_at")
        String updatedAt
    ) {
    }

    private record GithubUser(
        String login
    ) {
    }
}
