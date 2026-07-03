package com.repoguard.agent.github;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.repoguard.agent.config.GithubIntegrationSettings;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalCallErrorClassifier;
import com.repoguard.agent.external.ExternalCallResilience;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class GithubCommentWriter {

    private final RestClient restClient;
    private final GithubIntegrationHealthReporter healthReporter;

    public GithubCommentWriter(
        RestClient.Builder restClientBuilder,
        GithubIntegrationHealthReporter healthReporter
    ) {
        this.restClient = restClientBuilder.clone().build();
        this.healthReporter = healthReporter;
    }

    public List<GithubReviewCommentResult> publishPullRequestComments(
        GithubIntegrationSettings settings,
        String baseUrl,
        String owner,
        String repository,
        ReviewTask task,
        List<GithubReviewCommentDraft> drafts,
        ExternalCallResilience resilience
    ) {
        if (!StringUtils.hasText(settings.token())) {
            throw new IllegalStateException("GitHub token is not configured");
        }

        LocalDateTime startedAt = LocalDateTime.now();
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
                GithubCommentTargetType draftTargetType = GithubCommentTargetType.from(draft.targetType());
                GithubCommentTargetType actualTargetType = draftTargetType;
                if (draftTargetType.isPullRequest()) {
                    response = publishPullRequestComment(prCommentUrl, draft.body(), settings, resilience);
                } else {
                    if (!StringUtils.hasText(commitSha)) {
                        commitSha = resolvePullRequestHeadSha(baseUrl, owner, repository, task, settings, resilience);
                    }
                    try {
                        response = publishLineComment(lineCommentUrl, draft, commitSha, settings, resilience);
                    } catch (RuntimeException ex) {
                        if (!isUnresolvableLineComment(ex)) {
                            throw ex;
                        }
                        actualTargetType = GithubCommentTargetType.PULL_REQUEST;
                        response = publishPullRequestComment(prCommentUrl, draft.body(), settings, resilience);
                    }
                }
                boolean downgradedToPrComment = actualTargetType.isPullRequest() && !draftTargetType.isPullRequest();
                results.add(new GithubReviewCommentResult(
                    draft.findingId(),
                    draft.path(),
                    draft.line(),
                    actualTargetType.code(),
                    true,
                    downgradedToPrComment
                        ? GithubCommentPublicationStatus.DOWNGRADED_TO_PR_COMMENT.code()
                        : GithubCommentPublicationStatus.PUBLISHED.code(),
                    downgradedToPrComment
                        ? "GitHub line comment could not be resolved; published as PR comment"
                        : "GitHub comment published",
                    response == null ? null : response.htmlUrl(),
                    response == null ? null : response.id()
                ));
                healthReporter.markChecked(settings, null);
            } catch (RuntimeException ex) {
                RuntimeException classified = ExternalCallErrorClassifier.github(ex);
                failedCount++;
                healthReporter.recordGithubApiRequest(startedAt, "publish_pull_request_comments", "failed", classified);
                healthReporter.recordExternalFailure(classified);
                String message = healthReporter.conciseError(classified);
                results.add(new GithubReviewCommentResult(
                    draft.findingId(),
                    draft.path(),
                    draft.line(),
                    draft.targetType(),
                    false,
                    GithubCommentPublicationStatus.FAILED.code(),
                    message,
                    null,
                    null
                ));
                healthReporter.markChecked(settings, message);
            }
        }
        healthReporter.recordGithubApiRequest(
            startedAt,
            "publish_pull_request_comments",
            failedCount > 0 ? "partial" : "success",
            null,
            null
        );
        return results;
    }

    private GithubReviewCommentResponse publishPullRequestComment(
        String prCommentUrl,
        String body,
        GithubIntegrationSettings settings,
        ExternalCallResilience resilience
    ) {
        return executeGithub("publish_pull_request_comment", resilience, () -> restClient.post()
            .uri(prCommentUrl)
            .headers(headers -> applyGithubHeaders(headers, settings))
            .body(Map.of("body", body))
            .retrieve()
            .body(GithubReviewCommentResponse.class));
    }

    private GithubReviewCommentResponse publishLineComment(
        String lineCommentUrl,
        GithubReviewCommentDraft draft,
        String commitSha,
        GithubIntegrationSettings settings,
        ExternalCallResilience resilience
    ) {
        return executeGithub("publish_line_comment", resilience, () -> restClient.post()
            .uri(lineCommentUrl)
            .headers(headers -> applyGithubHeaders(headers, settings))
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
        GithubIntegrationSettings settings,
        ExternalCallResilience resilience
    ) {
        if (StringUtils.hasText(task.getCommitSha()) && task.getCommitSha().trim().matches("[a-fA-F0-9]{40}")) {
            return task.getCommitSha().trim();
        }
        String url = UriComponentsBuilder
            .fromUriString(baseUrl)
            .path("/repos/{owner}/{repo}/pulls/{pullNumber}")
            .build(owner, repository, task.getPrNumber())
            .toString();
        GithubPullRequestResponse response = executeGithub("resolve_pull_request_head", resilience, () -> restClient.get()
            .uri(url)
            .headers(headers -> applyGithubHeaders(headers, settings))
            .retrieve()
            .body(GithubPullRequestResponse.class));
        String sha = response == null || response.head() == null ? null : response.head().sha();
        if (!StringUtils.hasText(sha)) {
            throw new IllegalStateException("GitHub pull request head SHA is unavailable");
        }
        return sha.trim();
    }

    private void applyGithubHeaders(HttpHeaders headers, GithubIntegrationSettings settings) {
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        if (StringUtils.hasText(settings.token())) {
            headers.setBearerAuth(settings.token().trim());
        }
    }

    private <T> T executeGithub(
        String operation,
        ExternalCallResilience resilience,
        java.util.function.Supplier<T> supplier
    ) {
        return resilience == null ? supplier.get() : resilience.github(operation, supplier);
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
}
