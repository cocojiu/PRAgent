package com.repoguard.agent.github;

import com.fasterxml.jackson.annotation.JsonProperty;
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
        return executeGithub("publish_pull_request_comment", resilience, () -> restClient.post()
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

    GithubReviewCommentDetail[] listReviewComments(
        String url,
        GithubIntegrationSettings settings,
        ExternalCallResilience resilience
    ) {
        return executeGithub("list_pull_request_review_comments", resilience, () -> restClient.get()
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
}

record GithubCommentResponse(
    Long id,
    @JsonProperty("html_url")
    String htmlUrl
) {
}

record GithubReviewResponse(
    Long id,
    @JsonProperty("html_url")
    String htmlUrl
) {
}

record GithubReviewCommentDetail(
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
