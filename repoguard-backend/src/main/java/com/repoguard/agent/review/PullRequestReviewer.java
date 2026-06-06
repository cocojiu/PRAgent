package com.repoguard.agent.review;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubPullRequestDiff;

public interface PullRequestReviewer {

    ReviewResult review(ReviewTask task, GithubPullRequestDiff diff);
}
