package com.repoguard.agent.review;

import com.repoguard.agent.entity.ReviewTask;

public interface PullRequestReviewer {

    ReviewResult review(ReviewTask task, PullRequestDiff diff);

    default ReviewResult review(ReviewTask task, PullRequestDiff diff, ReviewDeadline deadline) {
        deadline.requireRemaining("review");
        return review(task, diff);
    }
}
