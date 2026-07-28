package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.notification.NotificationDispatchService;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

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

        notifier.reviewFinished(task, 3);
        notifier.reviewFailed(task);

        verify(notificationDispatchService).reviewFinished(task, 3);
        verify(notificationDispatchService).reviewFailed(task);
        verify(metricsRecorder).recordStage(
            Duration.ofSeconds(1),
            "notification_outbox_review_completed",
            "success"
        );
        verify(metricsRecorder).recordStage(Duration.ofSeconds(2), "notification_outbox_review_failed", "success");
    }

    @Test
    void propagatesOutboxFailureSoTerminalTransactionCanRollBack() {
        NotificationDispatchService notificationDispatchService =
            org.mockito.Mockito.mock(NotificationDispatchService.class);
        ReviewExecutionMetricsRecorder metricsRecorder = org.mockito.Mockito.mock(ReviewExecutionMetricsRecorder.class);
        TestReviewExecutionClock clock = new TestReviewExecutionClock();
        ReviewExecutionNotifier notifier = new ReviewExecutionNotifier(notificationDispatchService, clock, metricsRecorder);
        ReviewTask task = new ReviewTask();
        clock.setTimes("2026-07-09T10:05:00", "2026-07-09T10:05:01");
        doThrow(new IllegalStateException("outbox insert failed"))
            .when(notificationDispatchService)
            .reviewFinished(task, 5);

        assertThatThrownBy(() -> notifier.reviewFinished(task, 5))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("outbox insert failed");

        verify(notificationDispatchService).reviewFinished(task, 5);
        verify(metricsRecorder).recordStage(
            Duration.ofSeconds(1),
            "notification_outbox_review_completed",
            "failed"
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
