package com.repoguard.agent.github;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.repoguard.agent.github.GithubIntegrationSettings;
import com.repoguard.agent.external.ExternalCallResilience;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class GithubPullRequestReader {

    private final GithubPaginator paginator;

    public GithubPullRequestReader(GithubPaginator paginator) {
        this.paginator = paginator;
    }

    public List<GithubPullRequestSummary> listOpenPullRequests(
        GithubIntegrationSettings settings,
        String baseUrl,
        String owner,
        String repository,
        ExternalCallResilience resilience
    ) {
        List<GithubPullRequestListItem> items = paginator.fetchPages(
            "list_open_pull_requests",
            page -> UriComponentsBuilder
                .fromUriString(baseUrl)
                .path("/repos/{owner}/{repo}/pulls")
                .queryParam("state", "open")
                .queryParam("sort", "updated")
                .queryParam("direction", "desc")
                .queryParam("per_page", GithubPaginator.PAGE_SIZE)
                .queryParam("page", page)
                .build(owner.trim(), repository.trim())
                .toString(),
            settings,
            GithubPullRequestListItem[].class,
            resilience
        );
        return items.stream()
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

    private record GithubPullRequestHead(
        String ref,
        String sha
    ) {
    }

    private record GithubUser(
        String login
    ) {
    }
}
