package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.notification.NotificationDispatchService;
import org.junit.jupiter.api.Test;

class ReviewExecutionNotifierTest {

    @Test
    void delegatesFinishedAndFailedNotifications() {
        NotificationDispatchService notificationDispatchService =
            org.mockito.Mockito.mock(NotificationDispatchService.class);
        ReviewExecutionNotifier notifier = new ReviewExecutionNotifier(notificationDispatchService);
        ReviewTask task = new ReviewTask();

        notifier.reviewFinished(task, 3);
        notifier.reviewFailed(task);

        verify(notificationDispatchService).reviewFinished(task, 3);
        verify(notificationDispatchService).reviewFailed(task);
    }

    @Test
    void requiresNotificationDispatchServiceDependency() {
        assertThatThrownBy(() -> new ReviewExecutionNotifier(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("notificationDispatchService");
    }
}
