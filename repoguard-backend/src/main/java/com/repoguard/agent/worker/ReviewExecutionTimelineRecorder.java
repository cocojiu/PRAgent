package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubPullRequestHeadChangedException;
import com.repoguard.agent.review.ReviewResult;
import com.repoguard.agent.timeline.ReviewTimelineAppender;
import com.repoguard.agent.timeline.ReviewTimelineStatus;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class ReviewExecutionTimelineRecorder {

    private final ReviewTimelineAppender timelineAppender;
    private final ReviewExecutionTimelineLabelFormatter labelFormatter;

    ReviewExecutionTimelineRecorder(
        ReviewTimelineAppender timelineAppender,
        ReviewExecutionTimelineLabelFormatter labelFormatter
    ) {
        this.timelineAppender = Objects.requireNonNull(timelineAppender, "timelineAppender");
        this.labelFormatter = Objects.requireNonNull(labelFormatter, "labelFormatter");
    }

    void reviewStarted(ReviewTask task, LocalDateTime eventTime) {
        timelineAppender.append(task.getId(), "Review started", eventTime, ReviewTimelineStatus.CURRENT, 2);
    }

    void diffFetched(ReviewTask task, LocalDateTime eventTime) {
        timelineAppender.append(task.getId(), "GitHub diff fetched", eventTime, ReviewTimelineStatus.DONE, 3);
    }

    void reviewGenerated(ReviewTask task, ReviewResult reviewResult, LocalDateTime eventTime) {
        timelineAppender.append(task.getId(), labelFormatter.reviewGenerated(reviewResult), eventTime, ReviewTimelineStatus.DONE, 4);
    }

    void reviewTerminal(ReviewTask task, boolean humanReviewRequired, LocalDateTime eventTime) {
        timelineAppender.append(
            task.getId(),
            labelFormatter.reviewTerminal(humanReviewRequired),
            eventTime,
            humanReviewRequired ? ReviewTimelineStatus.CURRENT : ReviewTimelineStatus.DONE,
            5
        );
    }

    void reviewFailed(ReviewTask task, RuntimeException ex, LocalDateTime eventTime) {
        timelineAppender.append(task.getId(), labelFormatter.reviewFailed(ex), eventTime, ReviewTimelineStatus.FAILED, 5);
    }

    void reviewSuperseded(
        ReviewTask task,
        GithubPullRequestHeadChangedException ex,
        LocalDateTime eventTime
    ) {
        timelineAppender.append(
            task.getId(),
            labelFormatter.reviewSuperseded(ex),
            eventTime,
            ReviewTimelineStatus.DONE,
            5
        );
    }
}
