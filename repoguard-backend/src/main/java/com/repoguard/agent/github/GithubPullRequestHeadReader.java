package com.repoguard.agent.github;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.external.ExternalHttpJsonResponseReader;
import com.repoguard.agent.external.ExternalHttpResponseProfile;
import com.repoguard.agent.external.OutboundEndpointPolicy;
import com.repoguard.agent.external.OutboundEndpointType;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class GithubPullRequestHeadReader {

    private final RestClient restClient;
    private final ExternalHttpJsonResponseReader jsonResponseReader;
    private final OutboundEndpointPolicy endpointPolicy;

    @Autowired
    public GithubPullRequestHeadReader(
        RestClient.Builder restClientBuilder,
        ExternalHttpJsonResponseReader jsonResponseReader,
        OutboundEndpointPolicy endpointPolicy
    ) {
        this(restClientBuilder, jsonResponseReader, endpointPolicy, true);
    }

    GithubPullRequestHeadReader(
        RestClient.Builder restClientBuilder,
        ExternalHttpJsonResponseReader jsonResponseReader
    ) {
        this(restClientBuilder, jsonResponseReader, null, true);
    }

    private GithubPullRequestHeadReader(
        RestClient.Builder restClientBuilder,
        ExternalHttpJsonResponseReader jsonResponseReader,
        OutboundEndpointPolicy endpointPolicy,
        boolean ignored
    ) {
        this.restClient = GithubRestClientFactory.build(Objects.requireNonNull(restClientBuilder, "restClientBuilder"));
        this.jsonResponseReader = Objects.requireNonNull(jsonResponseReader, "jsonResponseReader");
        this.endpointPolicy = endpointPolicy;
    }

    public String fetchHeadSha(
        GithubIntegrationSettings settings,
        String baseUrl,
        String owner,
        String repository,
        Integer pullNumber,
        ExternalCallResilience resilience
    ) {
        return fetchHead(settings, baseUrl, owner, repository, pullNumber, resilience).sha();
    }

    public GithubPullRequestHeadSnapshot fetchHead(
        GithubIntegrationSettings settings,
        String baseUrl,
        String owner,
        String repository,
        Integer pullNumber,
        ExternalCallResilience resilience
    ) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(resilience, "resilience");
        String url = UriComponentsBuilder
            .fromUriString(baseUrl)
            .path("/repos/{owner}/{repo}/pulls/{pullNumber}")
            .build(owner, repository, pullNumber)
            .toString();
        if (endpointPolicy != null) {
            endpointPolicy.validate(OutboundEndpointType.GITHUB, url);
        }
        GithubPullRequestResponse response = resilience.github("fetch_pull_request_head", () -> restClient.get()
            .uri(url)
            .headers(headers -> applyGithubHeaders(headers, settings))
            .exchange((request, clientResponse) -> readJsonResponse(clientResponse)));
        String sha = response == null || response.head() == null ? null : response.head().sha();
        if (!StringUtils.hasText(sha)) {
            throw new IllegalStateException("GitHub pull request head SHA is unavailable");
        }
        if (!StringUtils.hasText(response.updatedAt())) {
            throw new IllegalStateException("GitHub pull request updated_at is unavailable");
        }
        LocalDateTime updatedAt;
        try {
            updatedAt = OffsetDateTime.parse(response.updatedAt().trim())
                .withOffsetSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();
        } catch (RuntimeException ex) {
            throw new IllegalStateException("GitHub pull request updated_at is invalid", ex);
        }
        return new GithubPullRequestHeadSnapshot(sha.trim(), updatedAt);
    }

    private GithubPullRequestResponse readJsonResponse(
        org.springframework.http.client.ClientHttpResponse response
    ) throws IOException {
        return jsonResponseReader.readSuccessfulJson(
            response,
            GithubPullRequestResponse.class,
            "GitHub fetch_pull_request_head failed",
            ExternalHttpResponseProfile.GITHUB
        );
    }

    private void applyGithubHeaders(HttpHeaders headers, GithubIntegrationSettings settings) {
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        if (StringUtils.hasText(settings.token())) {
            headers.setBearerAuth(settings.token().trim());
        }
    }

    private record GithubPullRequestResponse(
        GithubPullRequestHead head,
        @JsonProperty("updated_at") String updatedAt
    ) {
    }

    private record GithubPullRequestHead(String sha) {
    }

    public record GithubPullRequestHeadSnapshot(String sha, LocalDateTime updatedAt) {
        public GithubPullRequestHeadSnapshot {
            Objects.requireNonNull(sha, "sha");
            Objects.requireNonNull(updatedAt, "updatedAt");
        }
    }
}
