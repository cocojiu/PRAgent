package com.repoguard.agent.github;

import com.repoguard.agent.config.GithubDiffBudgetProperties;
import com.repoguard.agent.github.GithubIntegrationSettings;
import com.repoguard.agent.external.ExternalCallResilience;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class GithubChangedFileReader {

    private final GithubPaginator paginator;
    private final GithubDiffBudgetProperties budgetProperties;

    @Autowired
    public GithubChangedFileReader(
        GithubPaginator paginator,
        GithubDiffBudgetProperties budgetProperties
    ) {
        this.paginator = paginator;
        this.budgetProperties = budgetProperties;
    }

    GithubChangedFileReader(GithubPaginator paginator) {
        this(paginator, new GithubDiffBudgetProperties());
    }

    public GithubChangedFileFetch fetchChangedFiles(
        GithubIntegrationSettings settings,
        String baseUrl,
        String owner,
        String repository,
        Integer pullNumber,
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
            GithubChangedFile[].class,
            resilience,
            budgetProperties.getMaxPages(),
            accumulator::acceptPage
        );
        return accumulator.finish(traversal);
    }
}
