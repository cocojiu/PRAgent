package com.repoguard.agent.github;

import com.repoguard.agent.config.GithubIntegrationSettings;
import com.repoguard.agent.external.ExternalCallResilience;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class GithubChangedFileReader {

    private final GithubPaginator paginator;

    public GithubChangedFileReader(GithubPaginator paginator) {
        this.paginator = paginator;
    }

    public List<GithubChangedFile> fetchChangedFiles(
        GithubIntegrationSettings settings,
        String baseUrl,
        String owner,
        String repository,
        Integer pullNumber,
        ExternalCallResilience resilience
    ) {
        return paginator.fetchPages(
            "fetch_pull_request_diff",
            page -> UriComponentsBuilder
                .fromUriString(baseUrl)
                .path("/repos/{owner}/{repo}/pulls/{pullNumber}/files")
                .queryParam("per_page", GithubPaginator.PAGE_SIZE)
                .queryParam("page", page)
                .build(owner, repository, pullNumber)
                .toString(),
            settings,
            GithubChangedFile[].class,
            resilience
        );
    }
}
