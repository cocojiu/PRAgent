package com.repoguard.agent.github;

import com.repoguard.agent.config.GithubDiffBudgetProperties;
import com.repoguard.agent.github.GithubIntegrationSettings;
import com.repoguard.agent.external.ExternalCallResilience;
import com.repoguard.agent.review.PullRequestChangedFile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class GithubChangedFileReader {

    private final GithubPaginator paginator;
    private final GithubDiffBudgetProperties budgetProperties;
    private final GithubChangedFileContextLoader contextLoader;

    @Autowired
    public GithubChangedFileReader(
        GithubPaginator paginator,
        GithubDiffBudgetProperties budgetProperties,
        GithubChangedFileContextLoader contextLoader
    ) {
        this.paginator = paginator;
        this.budgetProperties = budgetProperties;
        this.contextLoader = contextLoader;
    }

    GithubChangedFileReader(GithubPaginator paginator) {
        this.paginator = paginator;
        this.budgetProperties = new GithubDiffBudgetProperties();
        this.contextLoader = null;
    }

    public GithubChangedFileFetch fetchChangedFiles(
        GithubIntegrationSettings settings,
        String baseUrl,
        String owner,
        String repository,
        Integer pullNumber,
        ExternalCallResilience resilience
    ) {
        return fetchChangedFiles(
            settings,
            baseUrl,
            owner,
            repository,
            pullNumber,
            null,
            resilience
        );
    }

    public GithubChangedFileFetch fetchChangedFiles(
        GithubIntegrationSettings settings,
        String baseUrl,
        String owner,
        String repository,
        Integer pullNumber,
        String headSha,
        ExternalCallResilience resilience
    ) {
        GithubChangedFileBudgetAccumulator accumulator =
            new GithubChangedFileBudgetAccumulator(budgetProperties);
        GithubPaginator.PageTraversal traversal = paginator.traversePages(
            "fetch_pull_request_diff",
            page -> UriComponentsBuilder
                .fromUriString(baseUrl)
                .path("/repos/{owner}/{repo}/pulls/{pullNumber}/files")
                .queryParam("per_page", GithubPaginator.PAGE_SIZE)
                .queryParam("page", page)
                .build(owner, repository, pullNumber)
                .toString(),
            settings,
            PullRequestChangedFile[].class,
            resilience,
            budgetProperties.getMaxPages(),
            accumulator::acceptPage
        );
        GithubChangedFileFetch fetch = accumulator.finish(traversal);
        if (contextLoader == null) {
            return fetch;
        }
        return new GithubChangedFileFetch(
            contextLoader.load(
                settings,
                baseUrl,
                owner,
                repository,
                headSha,
                fetch.files(),
                resilience
            ),
            fetch.truncation()
        );
    }
}
