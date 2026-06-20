package com.repoguard.agent.service;

import com.repoguard.agent.dto.GithubPullRequestOptionsResponse;

public interface GithubPullRequestOptionService {

    GithubPullRequestOptionsResponse listConfiguredGithubPullRequests();
}
