package com.repoguard.agent.worker;

import com.repoguard.agent.dto.GithubCommentPublishResponse;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.notification.NotificationDispatchService;

class ReviewExecutionNoopNotificationDispatchService implements NotificationDispatchService {

    @Override
    public void reviewFinished(ReviewTask task, int findingCount) {
    }

    @Override
    public void reviewFailed(ReviewTask task) {
    }

    @Override
    public void githubCommentsPublished(ReviewTask task, GithubCommentPublishResponse response, Long batchId) {
    }

    @Override
    public void publishExistingEvent(Long eventId) {
    }
}
