package com.repoguard.agent.github.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.external.ExternalHttpJsonResponseReader;
import com.repoguard.agent.external.ExternalHttpResponseProfile;
import com.repoguard.agent.external.OutboundEndpointPolicy;
import com.repoguard.agent.external.OutboundEndpointType;
import com.repoguard.agent.github.GithubIntegrationProvider;
import com.repoguard.agent.github.GithubIntegrationSettings;
import com.repoguard.agent.github.GithubRestClientFactory;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

/** Reads GitHub state outside database transactions; never trusts association labels as write permission. */
@Component
public class GithubFeedbackVerifier {
    private final GithubIntegrationProvider provider;
    private final RestClient client;
    private final ExternalHttpJsonResponseReader reader;
    private final ExternalCallResilience resilience;
    private final OutboundEndpointPolicy endpointPolicy;

    public GithubFeedbackVerifier(GithubIntegrationProvider provider, RestClient.Builder builder,
        ExternalHttpJsonResponseReader reader, ExternalCallResilience resilience, OutboundEndpointPolicy endpointPolicy) {
        this.provider = provider;
        this.client = GithubRestClientFactory.build(builder);
        this.reader = reader;
        this.resilience = resilience;
        this.endpointPolicy = endpointPolicy;
    }

    public String rejection(GithubFeedbackCommand command) {
        GithubIntegrationSettings settings = provider.getSettingsForRepository(command.owner(), command.repository());
        if (settings.token() == null || settings.token().isBlank()) throw new IllegalStateException("GitHub credentials unavailable");
        JsonNode permission = get(settings, "/repos/{owner}/{repo}/collaborators/{actor}/permission",
            command.owner(), command.repository(), command.actor());
        if (!List.of("write", "admin").contains(permission.path("permission").asText())
            || permission.path("user").path("id").asLong() != command.actorId()) return "ACTOR_PERMISSION_DENIED";
        JsonNode comment = get(settings, "/repos/{owner}/{repo}/pulls/comments/{id}",
            command.owner(), command.repository(), command.commentId());
        if (comment.path("id").asLong() != command.commentId()
            || comment.path("in_reply_to_id").asLong() != command.parentCommentId()
            || comment.path("user").path("id").asLong() != command.actorId()
            || !"User".equals(comment.path("user").path("type").asText())
            || !command.headSha().equalsIgnoreCase(comment.path("commit_id").asText())
            || !command.bodyHash().equals(GithubFeedbackCommand.hash(comment.path("body").asText("").trim()))) {
            return "REMOTE_COMMENT_CHANGED";
        }
        JsonNode pull = get(settings, "/repos/{owner}/{repo}/pulls/{number}",
            command.owner(), command.repository(), command.prNumber());
        return command.headSha().equalsIgnoreCase(pull.path("head").path("sha").asText())
            && "open".equals(pull.path("state").asText()) ? null : "HEAD_CHANGED_OR_CLOSED";
    }

    private JsonNode get(GithubIntegrationSettings settings, String path, Object... variables) {
        String base = settings.baseUrl() == null || settings.baseUrl().isBlank() ? "https://api.github.com" : settings.baseUrl();
        String url = UriComponentsBuilder.fromUriString(base).path(path).buildAndExpand(variables).toUriString();
        endpointPolicy.validate(OutboundEndpointType.GITHUB, url);
        JsonNode response = resilience.github("verify_feedback", () -> client.get().uri(url).headers(headers -> {
            headers.setBearerAuth(settings.token());
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            headers.set("X-GitHub-Api-Version", "2022-11-28");
        }).exchange((request, result) -> reader.readSuccessfulTree(result, "GitHub feedback verification failed",
            ExternalHttpResponseProfile.GITHUB)));
        if (response == null) throw new IllegalStateException("GitHub verification response unavailable");
        return response;
    }
}
