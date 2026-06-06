package com.repoguard.agent.github;

import com.repoguard.agent.entity.ReviewTask;

public interface GithubPullRequestClient {

    GithubPullRequestDiff fetchPullRequestDiff(ReviewTask task);
}
