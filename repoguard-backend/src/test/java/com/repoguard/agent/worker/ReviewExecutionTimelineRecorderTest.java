package com.repoguard.agent.worker;

import static org.mockito.Mockito.verify;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.ReviewResult;
import com.repoguard.agent.timeline.ReviewTimelineAppender;
import com.repoguard.agent.timeline.ReviewTimelineStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewExecutionTimelineRecorderTest {

    private final ReviewTimelineAppender timelineAppender = org.mockito.Mockito.mock(ReviewTimelineAppender.class);
    private final ReviewExecutionTimelineRecorder recorder = new ReviewExecutionTimelineRecorder(timelineAppender);

    @Test
    void recordsReviewStartedTimeline() {
        LocalDateTime eventTime = LocalDateTime.parse("2026-07-04T20:30:00");

        recorder.reviewStarted(task(), eventTime);

        verify(timelineAppender).append(42L, "Review started", eventTime, ReviewTimelineStatus.CURRENT, 2);
    }

    @Test
    void recordsCompletedReviewTimelines() {
        LocalDateTime eventTime = LocalDateTime.parse("2026-07-04T20:31:00");
        ReviewResult reviewResult = ReviewResult.completed("LOW", List.of());

        recorder.diffFetched(task(), eventTime);
        recorder.reviewGenerated(task(), reviewResult, eventTime);
        recorder.reviewTerminal(task(), false, eventTime);

        verify(timelineAppender).append(42L, "GitHub diff fetched", eventTime, ReviewTimelineStatus.DONE, 3);
        verify(timelineAppender).append(42L, "Code review generated", eventTime, ReviewTimelineStatus.DONE, 4);
        verify(timelineAppender).append(42L, "Review completed", eventTime, ReviewTimelineStatus.DONE, 5);
    }

    @Test
    void recordsPartialFallbackTimeline() {
        LocalDateTime eventTime = LocalDateTime.parse("2026-07-04T20:32:00");
        ReviewResult reviewResult = ReviewResult.completed(
            "LOW",
            List.of(),
            "dashscope",
            "mimo-v2.5-pro",
            10354,
            "partial_fallback",
            "chunked=true; failedChunks=2",
            null,
            null,
            null,
            null
        );

        recorder.reviewGenerated(task(), reviewResult, eventTime);

        verify(timelineAppender).append(
            42L,
            "Code review generated with partial rule fallback",
            eventTime,
            ReviewTimelineStatus.DONE,
            4
        );
    }

    @Test
    void recordsHumanReviewAndFailureTimelines() {
        LocalDateTime eventTime = LocalDateTime.parse("2026-07-04T20:33:00");

        recorder.reviewTerminal(task(), true, eventTime);
        recorder.reviewFailed(task(), new IllegalStateException("github unavailable"), eventTime);

        verify(timelineAppender).append(42L, "Human review required", eventTime, ReviewTimelineStatus.CURRENT, 5);
        verify(timelineAppender).append(42L, "Review failed: github unavailable", eventTime, ReviewTimelineStatus.FAILED, 5);
    }

    private ReviewTask task() {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        return task;
    }
}
