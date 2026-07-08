package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.notification.NotificationDispatchService;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ReviewExecutionNotifierTest {

    @Test
    void delegatesFinishedAndFailedNotifications() {
        NotificationDispatchService notificationDispatchService =
            org.mockito.Mockito.mock(NotificationDispatchService.class);
        ReviewExecutionMetricsRecorder metricsRecorder = org.mockito.Mockito.mock(ReviewExecutionMetricsRecorder.class);
        TestReviewExecutionClock clock = new TestReviewExecutionClock();
        ReviewExecutionNotifier notifier = new ReviewExecutionNotifier(notificationDispatchService, clock, metricsRecorder);
        ReviewTask task = new ReviewTask();
        clock.setTimes(
            "2026-07-09T10:00:00",
            "2026-07-09T10:00:01",
            "2026-07-09T10:00:02",
            "2026-07-09T10:00:04"
        );

        notifier.reviewFinishedAfterCommit(task, 3);
        notifier.reviewFailedAfterCommit(task);

        verify(notificationDispatchService).reviewFinished(task, 3);
        verify(notificationDispatchService).reviewFailed(task);
        verify(metricsRecorder).recordStage(
            Duration.ofSeconds(1),
            "notification_enqueue_review_completed",
            "success"
        );
        verify(metricsRecorder).recordStage(Duration.ofSeconds(2), "notification_enqueue_review_failed", "success");
    }

    @Test
    void defersNotificationUntilTransactionCommit() {
        NotificationDispatchService notificationDispatchService =
            org.mockito.Mockito.mock(NotificationDispatchService.class);
        ReviewExecutionMetricsRecorder metricsRecorder = org.mockito.Mockito.mock(ReviewExecutionMetricsRecorder.class);
        TestReviewExecutionClock clock = new TestReviewExecutionClock();
        ReviewExecutionNotifier notifier = new ReviewExecutionNotifier(notificationDispatchService, clock, metricsRecorder);
        ReviewTask task = new ReviewTask();
        clock.setTimes("2026-07-09T10:05:00", "2026-07-09T10:05:01");

        TransactionSynchronizationManager.initSynchronization();
        try {
            notifier.reviewFinishedAfterCommit(task, 5);

            verifyNoInteractions(notificationDispatchService);
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(notificationDispatchService).reviewFinished(task, 5);
        verify(metricsRecorder).recordStage(
            Duration.ofSeconds(1),
            "notification_enqueue_review_completed",
            "success"
        );
    }

    @Test
    void requiresNotificationDispatchServiceDependency() {
        assertThatThrownBy(() -> new ReviewExecutionNotifier(
            null,
            new ReviewExecutionClock(),
            new ReviewExecutionMetricsRecorder(org.mockito.Mockito.mock(RepoGuardMetrics.class))
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("notificationDispatchService");
    }

    private static class TestReviewExecutionClock extends ReviewExecutionClock {

        private LocalDateTime[] times = new LocalDateTime[0];
        private int index;

        void setTimes(String... isoDateTimes) {
            times = java.util.Arrays.stream(isoDateTimes)
                .map(LocalDateTime::parse)
                .toArray(LocalDateTime[]::new);
            index = 0;
        }

        @Override
        LocalDateTime now() {
            return times[index++];
        }
    }
}
