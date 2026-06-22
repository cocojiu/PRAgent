package com.repoguard.agent.notification;

import com.repoguard.agent.dto.GithubCommentPublishResponse;
import com.repoguard.agent.entity.ReviewTask;
import org.springframework.stereotype.Component;

@Component
class NotificationDispatchRequestFactory {

    NotificationDispatchRequest reviewFinished(ReviewTask task, int findingCount) {
        String eventType = Boolean.TRUE.equals(task.getHumanReviewRequired())
            ? NotificationEventType.HUMAN_REVIEW_REQUIRED.code()
            : NotificationEventType.REVIEW_COMPLETED.code();
        return new NotificationDispatchRequest(eventType, null, findingCount, 0, 0, 0);
    }

    NotificationDispatchRequest reviewFailed() {
        return new NotificationDispatchRequest(NotificationEventType.REVIEW_FAILED.code(), null, 0, 0, 0, 0);
    }

    NotificationDispatchRequest githubCommentsPublished(GithubCommentPublishResponse response, Long batchId) {
        return new NotificationDispatchRequest(
            NotificationEventType.GITHUB_COMMENT_PUBLISHED.code(),
            batchId,
            safe(response.totalFindings()),
            safe(response.succeededCount()),
            safe(response.failedCount()),
            safe(response.skippedCount())
        );
    }

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }
}
