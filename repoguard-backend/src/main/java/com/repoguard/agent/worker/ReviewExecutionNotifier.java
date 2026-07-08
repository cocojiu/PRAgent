package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.notification.NotificationDispatchService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    void reviewFinishedAfterCommit(ReviewTask task, int findingCount) {
        afterCommit(() -> recordNotificationEnqueue("notification_enqueue_review_completed", () ->
            notificationDispatchService.reviewFinished(task, findingCount)
        ));
    }

    void reviewFailedAfterCommit(ReviewTask task) {
        afterCommit(() -> recordNotificationEnqueue("notification_enqueue_review_failed", () ->
            notificationDispatchService.reviewFailed(task)
        ));
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private void recordNotificationEnqueue(String stage, Runnable action) {
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
