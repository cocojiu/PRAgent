package com.repoguard.agent.notification;

import com.repoguard.agent.dto.GithubCommentPublishResponse;
import com.repoguard.agent.entity.ReviewTask;

public interface NotificationDispatchService {

    void reviewFinished(ReviewTask task, int findingCount);

    void reviewFailed(ReviewTask task);

    void githubCommentsPublished(ReviewTask task, GithubCommentPublishResponse response, Long batchId);

    void publishExistingEvent(Long eventId);
}
