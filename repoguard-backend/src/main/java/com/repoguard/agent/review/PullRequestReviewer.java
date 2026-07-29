package com.repoguard.agent.review;

import com.repoguard.agent.entity.ReviewTask;

public interface PullRequestReviewer {

    ReviewResult review(ReviewTask task, PullRequestDiff diff);
}
