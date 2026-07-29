package com.repoguard.agent.notification.dispatch;

import com.repoguard.agent.dto.GithubCommentPublishResponse;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.notification.NotificationEventType;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class NotificationDispatchRequestFactory {

    private final NotificationCounterNormalizer counterNormalizer;

    public NotificationDispatchRequestFactory(NotificationCounterNormalizer counterNormalizer) {
        this.counterNormalizer = Objects.requireNonNull(counterNormalizer, "counterNormalizer");
    }

    public NotificationDispatchRequest reviewFinished(ReviewTask task, int findingCount) {
        String eventType = Boolean.TRUE.equals(task.getHumanReviewRequired())
            ? NotificationEventType.HUMAN_REVIEW_REQUIRED.code()
            : NotificationEventType.REVIEW_COMPLETED.code();
        return new NotificationDispatchRequest(eventType, null, findingCount, 0, 0, 0);
    }

    public NotificationDispatchRequest reviewFailed() {
        return new NotificationDispatchRequest(NotificationEventType.REVIEW_FAILED.code(), null, 0, 0, 0, 0);
    }

    public NotificationDispatchRequest githubCommentsPublished(GithubCommentPublishResponse response, Long batchId) {
        return new NotificationDispatchRequest(
            NotificationEventType.GITHUB_COMMENT_PUBLISHED.code(),
            batchId,
            counterNormalizer.safe(response.totalFindings()),
            counterNormalizer.safe(response.succeededCount()),
            counterNormalizer.safe(response.failedCount()),
            counterNormalizer.safe(response.skippedCount())
        );
    }
}
