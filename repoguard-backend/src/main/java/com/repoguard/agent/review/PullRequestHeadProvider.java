package com.repoguard.agent.review;

import com.repoguard.agent.entity.ReviewTask;

@FunctionalInterface
public interface PullRequestHeadProvider {

    String fetchPullRequestHeadSha(ReviewTask task);
}
