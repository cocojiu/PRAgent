package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.timeline.ReviewTimelineAppender;
import com.repoguard.agent.timeline.ReviewTimelineStatus;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class ReviewTaskRecoveryTimelineRecorder {

    private final ReviewTimelineAppender timelineAppender;

    ReviewTaskRecoveryTimelineRecorder(ReviewTimelineAppender timelineAppender) {
        this.timelineAppender = Objects.requireNonNull(timelineAppender, "timelineAppender");
    }

    void requeuePending(ReviewTask task, LocalDateTime eventTime) {
        timelineAppender.append(
            task.getId(),
            "Review execution timed out; requeue pending",
            eventTime,
            ReviewTimelineStatus.CURRENT,
            5
        );
    }

    void recoveryQueued(ReviewTask task, LocalDateTime eventTime) {
        timelineAppender.append(
            task.getId(),
            "Review execution timeout recovered; message requeued",
            eventTime,
            ReviewTimelineStatus.CURRENT,
            5
        );
    }

    void recoveryPublishFailed(ReviewTask task, LocalDateTime eventTime, String error) {
        timelineAppender.append(
            task.getId(),
            "Review execution recovery publish failed: " + error,
            eventTime,
            ReviewTimelineStatus.FAILED,
            5
        );
    }
}
