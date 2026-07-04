package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewTask;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class ReviewTaskRecoveryTimelineRecorder {

    private final ReviewTimelineAppender timelineAppender;

    ReviewTaskRecoveryTimelineRecorder(ReviewTimelineAppender timelineAppender) {
        this.timelineAppender = Objects.requireNonNull(timelineAppender, "timelineAppender");
    }

    void recoveryQueued(ReviewTask task, LocalDateTime eventTime) {
        timelineAppender.append(
            task.getId(),
            "Review execution timed out; queued for recovery",
            eventTime,
            "CURRENT",
            5
        );
    }
}
