package com.repoguard.agent.github;

import com.repoguard.agent.external.ExternalCallException;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.external.ExternalHttpJsonResponseReader;
import com.repoguard.agent.external.ExternalHttpResponseProfile;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Owns the HTTP protocol details shared by GitHub comment publication strategies.
 */
final class GithubCommentPublicationGateway {

    private final RestClient restClient;
    private final ExternalHttpJsonResponseReader jsonResponseReader;

    GithubCommentPublicationGateway(
        RestClient.Builder restClientBuilder,
        ExternalHttpJsonResponseReader jsonResponseReader
    ) {
        this.restClient = GithubRestClientFactory.build(Objects.requireNonNull(restClientBuilder, "restClientBuilder"));
        this.jsonResponseReader = Objects.requireNonNull(jsonResponseReader, "jsonResponseReader");
    }

    GithubCommentResponse publishPullRequestComment(
        String prCommentUrl,
        String body,
        GithubIntegrationSettings settings,
        ExternalCallResilience resilience
    ) {
        return executeGithubWrite("publish_pull_request_comment", resilience, () -> restClient.post()
            .uri(prCommentUrl)
            .headers(headers -> applyGithubHeaders(headers, settings))
            .body(Map.of("body", body))
            .exchange((request, response) -> readJsonResponse(
                response,
                GithubCommentResponse.class,
                "publish_pull_request_comment"
            )));
    }

    GithubCommentResponse publishLineComment(
        String lineCommentUrl,
        GithubReviewCommentDraft draft,
        String commitSha,
        GithubIntegrationSettings settings,
        ExternalCallResilience resilience
    ) {
        return executeGithubWrite("publish_line_comment", resilience, () -> restClient.post()
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
                GithubCommentResponse.class,
                "publish_line_comment"
            )));
    }

    GithubReviewResponse publishReview(
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
        return executeGithubWrite("publish_pull_request_review", resilience, () -> restClient.post()
            .uri(reviewUrl)
            .headers(headers -> applyGithubHeaders(headers, settings))
            .body(Map.of(
                "commit_id", commitSha,
                "event", "COMMENT",
                "body", lineDrafts.stream().map(draft -> GithubPublicationIntent.marker(draft.body()))
                    .filter(Objects::nonNull).collect(java.util.stream.Collectors.joining("\n")),
                "comments", comments
            ))
            .exchange((request, response) -> readJsonResponse(
                response,
                GithubReviewResponse.class,
                "publish_pull_request_review"
            )));
    }

    GithubReviewCommentDetail[] listReviewComments(
        String url,
        GithubIntegrationSettings settings,
        ExternalCallResilience resilience
    ) {
        return resilience.github("list_pull_request_review_comments", () -> restClient.get()
            .uri(url)
            .headers(headers -> applyGithubHeaders(headers, settings))
            .exchange((request, response) -> readJsonResponse(
                response,
                GithubReviewCommentDetail[].class,
                "list_pull_request_review_comments"
            )));
    }

    boolean isReviewValidationFailure(RuntimeException ex) {
        if (ex instanceof RestClientResponseException responseException) {
            return responseException.getStatusCode().value() == 422;
        }
        String message = ex.getMessage();
        return StringUtils.hasText(message) && message.contains("422");
    }

    boolean isUnresolvableLineComment(RuntimeException ex) {
        if (ex instanceof RestClientResponseException responseException) {
            return responseException.getStatusCode().value() == 422
                && containsUnresolvableLineSignal(responseException.getResponseBodyAsString());
        }
        String message = ex.getMessage();
        return StringUtils.hasText(message)
            && message.contains("422")
            && containsUnresolvableLineSignal(message);
    }

    private <T> T readJsonResponse(
        org.springframework.http.client.ClientHttpResponse response,
        Class<T> responseType,
        String operation
    ) throws IOException {
        T result = jsonResponseReader.readSuccessfulJson(
            response,
            responseType,
            "GitHub " + operation + " failed",
            ExternalHttpResponseProfile.GITHUB
        );
        if (responseType == GithubCommentResponse.class) {
            GithubCommentResponse comment = (GithubCommentResponse) result;
            requirePublicationIdentity(comment == null ? null : comment.id(), comment == null ? null : comment.htmlUrl());
        } else if (responseType == GithubReviewResponse.class) {
            GithubReviewResponse review = (GithubReviewResponse) result;
            requirePublicationIdentity(review == null ? null : review.id(), review == null ? null : review.htmlUrl());
        }
        return result;
    }

    private void requirePublicationIdentity(Long id, String url) {
        if (id == null || id <= 0 || !StringUtils.hasText(url)) {
            throw unknownOutcome(null);
        }
    }

    private <T> T executeGithubWrite(
        String operation,
        ExternalCallResilience resilience,
        java.util.function.Supplier<T> supplier
    ) {
        // A failed response cannot prove a non-idempotent POST was rejected.
        // Classify inside the retry decorator so it never repeats an ambiguous write.
        return resilience.github(operation, () -> {
            try {
                return supplier.get();
            } catch (RuntimeException ex) {
                if (ex instanceof RestClientResponseException responseException) {
                    int status = responseException.getStatusCode().value();
                    if (status >= 400 && status < 500 && status != 408) {
                        throw ex;
                    }
                }
                throw unknownOutcome(ex);
            }
        });
    }

    private ExternalCallException unknownOutcome(RuntimeException cause) {
        return new ExternalCallException("GitHub", "github_write_outcome_unknown", false, null,
            GithubCommentPublicationStatus.VERIFICATION_MESSAGE, cause);
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

}
