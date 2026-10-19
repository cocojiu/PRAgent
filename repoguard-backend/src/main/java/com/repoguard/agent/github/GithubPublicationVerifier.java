package com.repoguard.agent.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.external.ExternalHttpJsonResponseReader;
import com.repoguard.agent.external.ExternalHttpResponseProfile;
import com.repoguard.agent.external.OutboundEndpointPolicy;
import com.repoguard.agent.external.OutboundEndpointType;
import java.net.URI;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** Positive, bounded identity verification. Missing resources never authorize another POST. */
@Component
public class GithubPublicationVerifier {
    private final RestClient client;
    private final ExternalHttpJsonResponseReader reader;
    private final GithubIntegrationProvider integrations;
    private final ExternalCallResilience resilience;
    private final OutboundEndpointPolicy endpoints;
    private final GithubAppProperties app;
    public GithubPublicationVerifier(RestClient.Builder builder, ExternalHttpJsonResponseReader reader,
        GithubIntegrationProvider integrations, ExternalCallResilience resilience, OutboundEndpointPolicy endpoints,
        GithubAppProperties app) {
        this.client = GithubRestClientFactory.build(builder); this.reader = reader; this.integrations = integrations;
        this.resilience = resilience; this.endpoints = endpoints; this.app = app;
    }
    public Evidence verify(ReviewTask task, String marker, String kind, long remoteId) {
        if (remoteId < 1 || !GithubPublicationIntent.matchesCommit(marker, task.getCommitSha())) reject();
        var settings = integrations.getSettingsForRepository(task.getOrganization(), task.getRepository());
        if (settings == null) settings = integrations.getSettings();
        if (settings == null || !StringUtils.hasText(settings.token())) reject();
        String base = StringUtils.hasText(settings.baseUrl()) ? settings.baseUrl().trim() : "https://api.github.com";
        String owner = StringUtils.hasText(task.getOrganization()) ? task.getOrganization().trim() : settings.defaultOwner();
        String repository = StringUtils.hasText(task.getRepository()) ? task.getRepository().trim() : settings.defaultRepo();
        if (!StringUtils.hasText(owner) || !StringUtils.hasText(repository)) reject();
        String prefix = UriComponentsBuilder.fromUriString(base).path("/repos/{owner}/{repo}").build(owner, repository).toString();
        String path = switch (kind) {
            case "issue_comment" -> "/issues/comments/" + remoteId;
            case "review_comment" -> "/pulls/comments/" + remoteId;
            case "review" -> "/pulls/" + task.getPrNumber() + "/reviews/" + remoteId;
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "Unsupported GitHub reference kind");
        };
        JsonNode remote = get(prefix + path, settings);
        if (remote == null || remote.path("id").asLong() != remoteId || !remote.path("body").asText().contains(marker)) reject();
        String scope = "issue_comment".equals(kind) ? "issues" : "pulls";
        String expected = prefix + "/" + scope + "/" + task.getPrNumber();
        String actual = remote.path("issue_comment".equals(kind) ? "issue_url" : "pull_request_url").asText();
        if (!URI.create(expected).equals(URI.create(actual))) reject();
        if (!"issue_comment".equals(kind) && !task.getCommitSha().equalsIgnoreCase(remote.path("commit_id").asText())) reject();
        if (app.isEnabled()) {
            if (app.getAppId() == null || remote.path("performed_via_github_app").path("id").asLong() != app.getAppId()
                || !"Bot".equals(remote.path("user").path("type").asText())) reject();
        } else {
            JsonNode actor = get(base.replaceAll("/+$", "") + "/user", settings);
            if (actor == null || actor.path("id").asLong() <= 0
                || actor.path("id").asLong() != remote.path("user").path("id").asLong()) reject();
        }
        String url = remote.path("html_url").asText();
        URI link = URI.create(url);
        if (!"https".equalsIgnoreCase(link.getScheme()) || link.getHost() == null || link.getUserInfo() != null) reject();
        return new Evidence("review".equals(kind) ? null : remoteId, url, "issue_comment".equals(kind) ? "pull_request" : "line");
    }
    private JsonNode get(String url, GithubIntegrationSettings settings) {
        endpoints.validate(OutboundEndpointType.GITHUB, url);
        return resilience.github("verify_publication", () -> client.get().uri(url).headers(headers -> {
            headers.setBearerAuth(settings.token()); headers.set("X-GitHub-Api-Version", "2022-11-28");
            headers.set("Accept", "application/vnd.github+json");
        }).exchange((request, response) -> reader.readSuccessfulTree(response, "GitHub publication verification failed", ExternalHttpResponseProfile.GITHUB)));
    }
    private static void reject() {
        throw new BusinessException(ErrorCode.CONFLICT, "远端作者、任务版本、PR或核验标记无法确认；记录保持待核验。");
    }
    public record Evidence(Long id, String url, String targetType) { }
}
