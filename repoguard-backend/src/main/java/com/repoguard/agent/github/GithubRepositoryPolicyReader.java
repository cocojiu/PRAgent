package com.repoguard.agent.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.external.ExternalHttpJsonResponseReader;
import com.repoguard.agent.external.ExternalHttpResponseProfile;
import com.repoguard.agent.external.OutboundEndpointPolicy;
import com.repoguard.agent.external.OutboundEndpointType;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/** Reads the base-branch and PR-head policy file without accepting repository-controlled secrets. */
@Component
public class GithubRepositoryPolicyReader {

    private static final String POLICY_PATH = ".repoguard.yml";
    private static final String DEFAULT_BASE_URL = "https://api.github.com";

    private final GithubIntegrationProvider integrationProvider;
    private final GithubChangedFileContentReader contentReader;
    private final ExternalCallResilience resilience;
    private final ExternalHttpJsonResponseReader jsonReader;
    private final OutboundEndpointPolicy endpointPolicy;
    private final RestClient restClient;

    @Autowired
    public GithubRepositoryPolicyReader(
        GithubIntegrationProvider integrationProvider,
        GithubChangedFileContentReader contentReader,
        ExternalCallResilience resilience,
        ExternalHttpJsonResponseReader jsonReader,
        OutboundEndpointPolicy endpointPolicy,
        RestClient.Builder restClientBuilder
    ) {
        this.integrationProvider = Objects.requireNonNull(integrationProvider, "integrationProvider");
        this.contentReader = Objects.requireNonNull(contentReader, "contentReader");
        this.resilience = Objects.requireNonNull(resilience, "resilience");
        this.jsonReader = Objects.requireNonNull(jsonReader, "jsonReader");
        this.endpointPolicy = Objects.requireNonNull(endpointPolicy, "endpointPolicy");
        this.restClient = GithubRestClientFactory.build(Objects.requireNonNull(restClientBuilder, "restClientBuilder"));
    }

    public GithubRepositoryPolicyReader(
        GithubIntegrationProvider integrationProvider,
        GithubChangedFileContentReader contentReader,
        ExternalCallResilience resilience,
        ExternalHttpJsonResponseReader jsonReader
    ) {
        this.integrationProvider = Objects.requireNonNull(integrationProvider, "integrationProvider");
        this.contentReader = Objects.requireNonNull(contentReader, "contentReader");
        this.resilience = Objects.requireNonNull(resilience, "resilience");
        this.jsonReader = Objects.requireNonNull(jsonReader, "jsonReader");
        this.endpointPolicy = null;
        this.restClient = null;
    }

    public PolicySource readForTask(ReviewTask task) {
        if (task == null || !StringUtils.hasText(task.getOrganization()) || !StringUtils.hasText(task.getRepository())) {
            return PolicySource.empty("missing_repository");
        }
        return read(task.getOrganization(), task.getRepository(), task.getCommitSha(), true);
    }

    public PolicySource readForPreview(String organization, String repository, String headSha) {
        return read(organization, repository, headSha, true);
    }

    private PolicySource read(String organization, String repository, String headSha, boolean includeHead) {
        String owner = required(organization, "organization");
        String repo = required(repository, "repository");
        GithubIntegrationSettings settings = integrationProvider.getSettingsForRepository(owner, repo);
        if (settings == null || !settings.exists()) {
            return PolicySource.empty("github_integration_not_configured");
        }
        String baseUrl = StringUtils.hasText(settings.baseUrl()) ? settings.baseUrl().trim() : DEFAULT_BASE_URL;
        String defaultBranch = defaultBranch(settings, baseUrl, owner, repo);
        String baseContent = fetchOptional(settings, baseUrl, owner, repo, defaultBranch);
        String headContent = includeHead && StringUtils.hasText(headSha)
            ? fetchOptional(settings, baseUrl, owner, repo, headSha.trim())
            : null;
        return new PolicySource(baseContent, headContent, defaultBranch, null);
    }

    private String defaultBranch(
        GithubIntegrationSettings settings,
        String baseUrl,
        String owner,
        String repository
    ) {
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
            .pathSegment("repos", owner, repository)
            .build()
            .encode()
            .toUri();
        validate(uri);
        JsonNode root = resilience.github("fetch_repository_policy_metadata", () -> restClient.get()
            .uri(uri)
            .headers(headers -> applyHeaders(headers, settings))
            .exchange((request, response) -> jsonReader.readSuccessfulTree(
                response,
                "GitHub repository policy metadata failed",
                ExternalHttpResponseProfile.GITHUB
            )));
        String branch = root == null ? null : root.path("default_branch").asText(null);
        if (!StringUtils.hasText(branch) || branch.length() > 255 || branch.contains("..")) {
            throw new IllegalStateException("GitHub repository default branch is unavailable or unsafe");
        }
        return branch.trim();
    }

    private String fetchOptional(
        GithubIntegrationSettings settings,
        String baseUrl,
        String owner,
        String repository,
        String ref
    ) {
        try {
            return contentReader.fetch(settings, baseUrl, owner, repository, ref, POLICY_PATH, resilience);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                return null;
            }
            throw ex;
        }
    }

    private void applyHeaders(HttpHeaders headers, GithubIntegrationSettings settings) {
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        if (StringUtils.hasText(settings.token())) {
            headers.setBearerAuth(settings.token().trim());
        }
    }

    private void validate(URI uri) {
        if (endpointPolicy != null) {
            endpointPolicy.validate(OutboundEndpointType.GITHUB, uri.toString());
        }
    }

    private String required(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    public record PolicySource(String baseContent, String headContent, String baseRef, String error) {

        public static PolicySource empty(String error) {
            return new PolicySource(null, null, null, error);
        }

        public boolean hasBase() {
            return StringUtils.hasText(baseContent);
        }

        public boolean hasHead() {
            return StringUtils.hasText(headContent);
        }
    }
}
