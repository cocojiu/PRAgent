package com.repoguard.agent.worker;

import static org.mockito.Mockito.verify;

import com.repoguard.agent.entity.ReviewTask;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ReviewTaskRecoveryTimelineRecorderTest {

    private final ReviewTimelineAppender timelineAppender = org.mockito.Mockito.mock(ReviewTimelineAppender.class);
    private final ReviewTaskRecoveryTimelineRecorder recorder = new ReviewTaskRecoveryTimelineRecorder(timelineAppender);

    @Test
    void recordsRecoveryQueuedTimeline() {
        LocalDateTime eventTime = LocalDateTime.parse("2026-07-05T10:30:00");

        recorder.recoveryQueued(task(), eventTime);

        verify(timelineAppender).append(
            42L,
            "Review execution timed out; queued for recovery",
            eventTime,
            "CURRENT",
            5
        );
    }

    private ReviewTask task() {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        return task;
    }
}
