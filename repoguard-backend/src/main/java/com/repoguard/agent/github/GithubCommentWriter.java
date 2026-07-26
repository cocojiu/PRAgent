package com.repoguard.agent.github;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.repoguard.agent.config.GithubIntegrationSettings;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalCallErrorClassifier;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.external.ExternalHttpJsonResponseReader;
import com.repoguard.agent.external.ExternalHttpResponseProfile;
import com.repoguard.agent.external.OutboundEndpointPolicy;
import com.repoguard.agent.external.OutboundEndpointType;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class GithubCommentWriter {

    private final RestClient restClient;
    private final GithubIntegrationHealthReporter healthReporter;
    private final ExternalHttpJsonResponseReader jsonResponseReader;
    private final OutboundEndpointPolicy endpointPolicy;

    @Autowired
    public GithubCommentWriter(
        RestClient.Builder restClientBuilder,
        GithubIntegrationHealthReporter healthReporter,
        ExternalHttpJsonResponseReader jsonResponseReader,
        OutboundEndpointPolicy endpointPolicy
    ) {
        this.restClient = GithubRestClientFactory.build(Objects.requireNonNull(restClientBuilder, "restClientBuilder"));
        this.healthReporter = Objects.requireNonNull(healthReporter, "healthReporter");
        this.jsonResponseReader = Objects.requireNonNull(jsonResponseReader, "jsonResponseReader");
        this.endpointPolicy = Objects.requireNonNull(endpointPolicy, "endpointPolicy");
    }

    GithubCommentWriter(
        RestClient.Builder restClientBuilder,
        GithubIntegrationHealthReporter healthReporter,
        ExternalHttpJsonResponseReader jsonResponseReader
    ) {
        this.restClient = GithubRestClientFactory.build(Objects.requireNonNull(restClientBuilder, "restClientBuilder"));
        this.healthReporter = Objects.requireNonNull(healthReporter, "healthReporter");
        this.jsonResponseReader = Objects.requireNonNull(jsonResponseReader, "jsonResponseReader");
        this.endpointPolicy = null;
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
        ExternalCallResilience effectiveResilience = Objects.requireNonNull(resilience, "resilience");
        if (!StringUtils.hasText(settings.token())) {
            throw new IllegalStateException("GitHub token is not configured");
        }
        if (endpointPolicy != null) {
            endpointPolicy.validate(OutboundEndpointType.GITHUB, baseUrl);
        }

        LocalDateTime startedAt = LocalDateTime.now();
        String reviewUrl = UriComponentsBuilder
            .fromUriString(baseUrl)
            .path("/repos/{owner}/{repo}/pulls/{pullNumber}/reviews")
            .build(owner, repository, task.getPrNumber())
            .toString();
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

        GithubReviewCommentResult[] results = new GithubReviewCommentResult[drafts.size()];
        List<Integer> lineDraftIndexes = new ArrayList<>();
        int failedCount = 0;
        for (int index = 0; index < drafts.size(); index++) {
            GithubReviewCommentDraft draft = drafts.get(index);
            if (!GithubCommentTargetType.from(draft.targetType()).isPullRequest()) {
                lineDraftIndexes.add(index);
                continue;
            }
            try {
                GithubReviewCommentResponse response = publishPullRequestComment(
                    prCommentUrl,
                    draft.body(),
                    settings,
                    effectiveResilience
                );
                results[index] = publishedResult(draft, GithubCommentTargetType.PULL_REQUEST, false, response);
                healthReporter.markChecked(settings, null);
            } catch (RuntimeException ex) {
                failedCount++;
                results[index] = failedResult(startedAt, settings, draft, ex);
            }
        }

        failedCount += publishLineDrafts(
            settings,
            baseUrl,
            owner,
            repository,
            task,
            drafts,
            lineDraftIndexes,
            results,
            reviewUrl,
            lineCommentUrl,
            prCommentUrl,
            effectiveResilience,
            startedAt
        );

        healthReporter.recordGithubApiRequest(
            startedAt,
            "publish_pull_request_comments",
            failedCount > 0 ? "partial" : "success",
            null,
            null
        );
        return List.of(results);
    }

    private int publishLineDrafts(
        GithubIntegrationSettings settings,
        String baseUrl,
        String owner,
        String repository,
        ReviewTask task,
        List<GithubReviewCommentDraft> drafts,
        List<Integer> lineDraftIndexes,
        GithubReviewCommentResult[] results,
        String reviewUrl,
        String lineCommentUrl,
        String prCommentUrl,
        ExternalCallResilience resilience,
        LocalDateTime startedAt
    ) {
        if (lineDraftIndexes.isEmpty()) {
            return 0;
        }
        String commitSha;
        try {
            commitSha = resolvePullRequestHeadSha(baseUrl, owner, repository, task, settings, resilience);
        } catch (RuntimeException ex) {
            return failLineDrafts(startedAt, settings, drafts, lineDraftIndexes, results, ex);
        }
        List<GithubReviewCommentDraft> lineDrafts = lineDraftIndexes.stream().map(drafts::get).toList();
        GithubReviewResponse review;
        try {
            review = publishReview(reviewUrl, lineDrafts, commitSha, settings, resilience);
        } catch (RuntimeException ex) {
            if (isReviewValidationFailure(ex)) {
                return publishLineDraftsIndividually(
                    settings,
                    drafts,
                    lineDraftIndexes,
                    results,
                    lineCommentUrl,
                    prCommentUrl,
                    commitSha,
                    resilience,
                    startedAt
                );
            }
            return failLineDrafts(startedAt, settings, drafts, lineDraftIndexes, results, ex);
        }
        List<GithubReviewCommentDetail> reviewComments = listReviewCommentsQuietly(reviewUrl, review, settings, resilience);
        List<GithubReviewCommentDetail> unmatched = new ArrayList<>(reviewComments);
        String reviewUrlFallback = review == null ? null : review.htmlUrl();
        for (Integer index : lineDraftIndexes) {
            GithubReviewCommentDraft draft = drafts.get(index);
            GithubReviewCommentDetail matched = takeMatchingReviewComment(unmatched, draft);
            results[index] = new GithubReviewCommentResult(
                draft.findingId(),
                draft.path(),
                draft.line(),
                GithubCommentTargetType.LINE.code(),
                true,
                GithubCommentPublicationStatus.PUBLISHED.code(),
                "GitHub comment published",
                matched != null && StringUtils.hasText(matched.htmlUrl()) ? matched.htmlUrl() : reviewUrlFallback,
                matched == null ? null : matched.id()
            );
        }
        healthReporter.markChecked(settings, null);
        return 0;
    }

    private int publishLineDraftsIndividually(
        GithubIntegrationSettings settings,
        List<GithubReviewCommentDraft> drafts,
        List<Integer> lineDraftIndexes,
        GithubReviewCommentResult[] results,
        String lineCommentUrl,
        String prCommentUrl,
        String commitSha,
        ExternalCallResilience resilience,
        LocalDateTime startedAt
    ) {
        int failedCount = 0;
        for (Integer index : lineDraftIndexes) {
            GithubReviewCommentDraft draft = drafts.get(index);
            try {
                GithubCommentTargetType actualTargetType = GithubCommentTargetType.LINE;
                GithubReviewCommentResponse response;
                try {
                    response = publishLineComment(lineCommentUrl, draft, commitSha, settings, resilience);
                } catch (RuntimeException ex) {
                    if (!isUnresolvableLineComment(ex)) {
                        throw ex;
                    }
                    actualTargetType = GithubCommentTargetType.PULL_REQUEST;
                    response = publishPullRequestComment(prCommentUrl, draft.body(), settings, resilience);
                }
                results[index] = publishedResult(draft, actualTargetType, actualTargetType.isPullRequest(), response);
                healthReporter.markChecked(settings, null);
            } catch (RuntimeException ex) {
                failedCount++;
                results[index] = failedResult(startedAt, settings, draft, ex);
            }
        }
        return failedCount;
    }

    private int failLineDrafts(
        LocalDateTime startedAt,
        GithubIntegrationSettings settings,
        List<GithubReviewCommentDraft> drafts,
        List<Integer> lineDraftIndexes,
        GithubReviewCommentResult[] results,
        RuntimeException ex
    ) {
        RuntimeException classified = ExternalCallErrorClassifier.github(ex);
        healthReporter.recordGithubApiRequest(startedAt, "publish_pull_request_comments", "failed", classified);
        healthReporter.recordExternalFailure(classified);
        String message = healthReporter.conciseError(classified);
        for (Integer index : lineDraftIndexes) {
            GithubReviewCommentDraft draft = drafts.get(index);
            results[index] = new GithubReviewCommentResult(
                draft.findingId(),
                draft.path(),
                draft.line(),
                draft.targetType(),
                false,
                GithubCommentPublicationStatus.FAILED.code(),
                message,
                null,
                null
            );
        }
        healthReporter.markChecked(settings, message);
        return lineDraftIndexes.size();
    }

    private GithubReviewCommentResult publishedResult(
        GithubReviewCommentDraft draft,
        GithubCommentTargetType actualTargetType,
        boolean downgradedToPrComment,
        GithubReviewCommentResponse response
    ) {
        return new GithubReviewCommentResult(
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
        );
    }

    private GithubReviewCommentResult failedResult(
        LocalDateTime startedAt,
        GithubIntegrationSettings settings,
        GithubReviewCommentDraft draft,
        RuntimeException ex
    ) {
        RuntimeException classified = ExternalCallErrorClassifier.github(ex);
        healthReporter.recordGithubApiRequest(startedAt, "publish_pull_request_comments", "failed", classified);
        healthReporter.recordExternalFailure(classified);
        String message = healthReporter.conciseError(classified);
        GithubReviewCommentResult result = new GithubReviewCommentResult(
            draft.findingId(),
            draft.path(),
            draft.line(),
            draft.targetType(),
            false,
            GithubCommentPublicationStatus.FAILED.code(),
            message,
            null,
            null
        );
        healthReporter.markChecked(settings, message);
        return result;
    }

    private GithubReviewResponse publishReview(
        String reviewUrl,
        List<GithubReviewCommentDraft> lineDrafts,
        String commitSha,
        GithubIntegrationSettings settings,
        ExternalCallResilience resilience
    ) {
        List<Map<String, Object>> comments = lineDrafts.stream()
            .map(draft -> Map.<String, Object>of(
                "path", draft.path(),
                "line", draft.line(),
                "side", "RIGHT",
                "body", draft.body()
            ))
            .toList();
        return executeGithub("publish_pull_request_review", resilience, () -> restClient.post()
            .uri(reviewUrl)
            .headers(headers -> applyGithubHeaders(headers, settings))
            .body(Map.of(
                "commit_id", commitSha,
                "event", "COMMENT",
                "body", "",
                "comments", comments
            ))
            .exchange((request, response) -> readJsonResponse(
                response,
                GithubReviewResponse.class,
                "publish_pull_request_review"
            )));
    }

    private List<GithubReviewCommentDetail> listReviewCommentsQuietly(
        String reviewUrl,
        GithubReviewResponse review,
        GithubIntegrationSettings settings,
        ExternalCallResilience resilience
    ) {
        if (review == null || review.id() == null) {
            return List.of();
        }
        String url = reviewUrl + "/" + review.id() + "/comments?per_page=100";
        try {
            GithubReviewCommentDetail[] comments = executeGithub(
                "list_pull_request_review_comments",
                resilience,
                () -> restClient.get()
                    .uri(url)
                    .headers(headers -> applyGithubHeaders(headers, settings))
                    .exchange((request, response) -> readJsonResponse(
                        response,
                        GithubReviewCommentDetail[].class,
                        "list_pull_request_review_comments"
                    ))
            );
            return comments == null ? List.of() : Arrays.asList(comments);
        } catch (RuntimeException ex) {
            RuntimeException classified = ExternalCallErrorClassifier.github(ex);
            healthReporter.recordExternalFailure(classified);
            return List.of();
        }
    }

    private GithubReviewCommentDetail takeMatchingReviewComment(
        List<GithubReviewCommentDetail> comments,
        GithubReviewCommentDraft draft
    ) {
        GithubReviewCommentDetail matched = null;
        for (GithubReviewCommentDetail comment : comments) {
            if (!matchesDraftLocation(comment, draft)) {
                continue;
            }
            if (Objects.equals(comment.body(), draft.body())) {
                matched = comment;
                break;
            }
            if (matched == null) {
                matched = comment;
            }
        }
        if (matched != null) {
            comments.remove(matched);
        }
        return matched;
    }

    private boolean matchesDraftLocation(GithubReviewCommentDetail comment, GithubReviewCommentDraft draft) {
        return Objects.equals(comment.path(), draft.path())
            && (Objects.equals(comment.line(), draft.line()) || Objects.equals(comment.originalLine(), draft.line()));
    }

    private boolean isReviewValidationFailure(RuntimeException ex) {
        if (ex instanceof RestClientResponseException responseException) {
            return responseException.getStatusCode().value() == 422;
        }
        String message = ex.getMessage();
        return StringUtils.hasText(message) && message.contains("422");
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
            .exchange((request, response) -> readJsonResponse(
                response,
                GithubReviewCommentResponse.class,
                "publish_pull_request_comment"
            )));
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
            .exchange((request, response) -> readJsonResponse(
                response,
                GithubReviewCommentResponse.class,
                "publish_line_comment"
            )));
    }

    private boolean isUnresolvableLineComment(RuntimeException ex) {
        if (ex instanceof RestClientResponseException responseException) {
            return responseException.getStatusCode().value() == 422
                && containsUnresolvableLineSignal(responseException.getResponseBodyAsString());
        }
        String message = ex.getMessage();
        return StringUtils.hasText(message)
            && message.contains("422")
            && containsUnresolvableLineSignal(message);
    }

    private String resolvePullRequestHeadSha(
        String baseUrl,
        String owner,
        String repository,
        ReviewTask task,
        GithubIntegrationSettings settings,
        ExternalCallResilience resilience
    ) {
        if (StringUtils.hasText(task.getCommitSha()) && task.getCommitSha().trim().matches("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")) {
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
            .exchange((request, clientResponse) -> readJsonResponse(
                clientResponse,
                GithubPullRequestResponse.class,
                "resolve_pull_request_head"
            )));
        String sha = response == null || response.head() == null ? null : response.head().sha();
        if (!StringUtils.hasText(sha)) {
            throw new IllegalStateException("GitHub pull request head SHA is unavailable");
        }
        return sha.trim();
    }

    private <T> T readJsonResponse(
        org.springframework.http.client.ClientHttpResponse response,
        Class<T> responseType,
        String operation
    ) throws IOException {
        return jsonResponseReader.readSuccessfulJson(
            response,
            responseType,
            "GitHub " + operation + " failed",
            ExternalHttpResponseProfile.GITHUB
        );
    }

    private boolean containsUnresolvableLineSignal(String value) {
        return StringUtils.hasText(value) && value.contains("could not be resolved");
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
        return resilience.github(operation, supplier);
    }

    private record GithubReviewCommentResponse(
        Long id,
        @JsonProperty("html_url")
        String htmlUrl
    ) {
    }

    private record GithubReviewResponse(
        Long id,
        @JsonProperty("html_url")
        String htmlUrl
    ) {
    }

    private record GithubReviewCommentDetail(
        Long id,
        @JsonProperty("html_url")
        String htmlUrl,
        String path,
        Integer line,
        @JsonProperty("original_line")
        Integer originalLine,
        String body
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
