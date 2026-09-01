package com.repoguard.agent.review;

import com.repoguard.agent.entity.ReviewTask;

/** Application port for publishing review task lifecycle changes to an external check provider. */
public interface ReviewTaskCheckRunLifecycle {

    void queued(ReviewTask task);

    void inProgress(ReviewTask task);

    void completed(ReviewTask task);
}
