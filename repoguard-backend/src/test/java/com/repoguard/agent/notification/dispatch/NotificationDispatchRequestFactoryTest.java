package com.repoguard.agent.notification.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.dto.GithubCommentPublishResponse;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.notification.NotificationEventType;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationDispatchRequestFactoryTest {

    private final NotificationDispatchRequestFactory factory =
        new NotificationDispatchRequestFactory(new NotificationCounterNormalizer());

    @Test
    void reviewFinishedUsesHumanReviewEventWhenManualReviewRequired() {
        ReviewTask task = new ReviewTask();
        task.setHumanReviewRequired(true);

        NotificationDispatchRequest request = factory.reviewFinished(task, 7);

        assertThat(request.eventType()).isEqualTo(NotificationEventType.HUMAN_REVIEW_REQUIRED.code());
        assertThat(request.findingCount()).isEqualTo(7);
        assertThat(request.batchId()).isNull();
    }

    @Test
    void reviewFinishedUsesCompletedEventWhenHumanReviewNotRequired() {
        ReviewTask task = new ReviewTask();
        task.setHumanReviewRequired(false);

        NotificationDispatchRequest request = factory.reviewFinished(task, 3);

        assertThat(request.eventType()).isEqualTo(NotificationEventType.REVIEW_COMPLETED.code());
        assertThat(request.findingCount()).isEqualTo(3);
    }

    @Test
    void githubCommentsPublishedDefaultsNullCountersToZero() {
        GithubCommentPublishResponse response = new GithubCommentPublishResponse(
            99L,
            null,
            null,
            null,
            null,
            null,
            List.of()
        );

        NotificationDispatchRequest request = factory.githubCommentsPublished(response, 99L);

        assertThat(request.eventType()).isEqualTo(NotificationEventType.GITHUB_COMMENT_PUBLISHED.code());
        assertThat(request.batchId()).isEqualTo(99L);
        assertThat(request.findingCount()).isZero();
        assertThat(request.commentSucceededCount()).isZero();
        assertThat(request.commentFailedCount()).isZero();
        assertThat(request.commentSkippedCount()).isZero();
    }

    @Test
    void reviewFailedBuildsFailureEventRequest() {
        NotificationDispatchRequest request = factory.reviewFailed();

        assertThat(request.eventType()).isEqualTo(NotificationEventType.REVIEW_FAILED.code());
        assertThat(request.batchId()).isNull();
        assertThat(request.findingCount()).isZero();
    }
}
