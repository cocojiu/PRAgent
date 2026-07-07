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
    private final ReviewTaskRecoveryTimelineLabelFormatter labelFormatter;

    ReviewTaskRecoveryTimelineRecorder(
        ReviewTimelineAppender timelineAppender,
        ReviewTaskRecoveryTimelineLabelFormatter labelFormatter
    ) {
        this.timelineAppender = Objects.requireNonNull(timelineAppender, "timelineAppender");
        this.labelFormatter = Objects.requireNonNull(labelFormatter, "labelFormatter");
    }

    void requeuePending(ReviewTask task, LocalDateTime eventTime) {
        timelineAppender.append(
            task.getId(),
            labelFormatter.requeuePending(),
            eventTime,
            ReviewTimelineStatus.CURRENT,
            5
        );
    }

    void recoveryQueued(ReviewTask task, LocalDateTime eventTime) {
        timelineAppender.append(
            task.getId(),
            labelFormatter.recoveryQueued(),
            eventTime,
            ReviewTimelineStatus.CURRENT,
            5
        );
    }

    void recoveryPublishFailed(ReviewTask task, LocalDateTime eventTime, String error) {
        timelineAppender.append(
            task.getId(),
            labelFormatter.recoveryPublishFailed(error),
            eventTime,
            ReviewTimelineStatus.FAILED,
            5
        );
    }
}
