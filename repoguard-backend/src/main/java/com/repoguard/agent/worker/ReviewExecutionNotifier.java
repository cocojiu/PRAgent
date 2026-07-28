package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.notification.NotificationDispatchService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class ReviewExecutionNotifier {

    private final NotificationDispatchService notificationDispatchService;
    private final ReviewExecutionClock clock;
    private final ReviewExecutionMetricsRecorder metricsRecorder;

    ReviewExecutionNotifier(
        NotificationDispatchService notificationDispatchService,
        ReviewExecutionClock clock,
        ReviewExecutionMetricsRecorder metricsRecorder
    ) {
        this.notificationDispatchService = Objects.requireNonNull(
            notificationDispatchService,
            "notificationDispatchService"
        );
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metricsRecorder = Objects.requireNonNull(metricsRecorder, "metricsRecorder");
    }

    void reviewFinished(ReviewTask task, int findingCount) {
        recordNotificationOutbox("notification_outbox_review_completed", () ->
            notificationDispatchService.reviewFinished(task, findingCount)
        );
    }

    void reviewFailed(ReviewTask task) {
        recordNotificationOutbox("notification_outbox_review_failed", () ->
            notificationDispatchService.reviewFailed(task)
        );
    }

    private void recordNotificationOutbox(String stage, Runnable action) {
        LocalDateTime startedAt = clock.now();
        try {
            action.run();
            metricsRecorder.recordStage(Duration.between(startedAt, clock.now()), stage, "success");
        } catch (RuntimeException ex) {
            metricsRecorder.recordStage(Duration.between(startedAt, clock.now()), stage, "failed");
            throw ex;
        }
    }
}
