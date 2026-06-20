package com.repoguard.agent.service.impl;

import com.repoguard.agent.dto.GithubPullRequestOption;
import com.repoguard.agent.dto.GithubPullRequestOptionsResponse;
import com.repoguard.agent.github.GithubPullRequestClient;
import com.repoguard.agent.github.GithubPullRequestSummary;
import com.repoguard.agent.github.GithubRepositoryRef;
import com.repoguard.agent.service.GithubPullRequestOptionService;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class GithubPullRequestOptionServiceImpl implements GithubPullRequestOptionService {

    private final GithubPullRequestClient githubPullRequestClient;

    public GithubPullRequestOptionServiceImpl(GithubPullRequestClient githubPullRequestClient) {
        this.githubPullRequestClient = githubPullRequestClient;
    }

    @Override
    public GithubPullRequestOptionsResponse listConfiguredGithubPullRequests() {
        GithubRepositoryRef repositoryRef = githubPullRequestClient.getConfiguredRepository();
        List<GithubPullRequestSummary> pullRequests = githubPullRequestClient.listOpenPullRequests();
        return new GithubPullRequestOptionsResponse(
            repositoryRef.owner(),
            repositoryRef.repository(),
            pullRequests.stream()
                .map(item -> new GithubPullRequestOption(
                    item.number(),
                    item.title(),
                    item.branch(),
                    item.commit(),
                    item.commit(),
                    item.author(),
                    item.url(),
                    item.updatedAt()
                ))
                .toList()
        );
    }
}
