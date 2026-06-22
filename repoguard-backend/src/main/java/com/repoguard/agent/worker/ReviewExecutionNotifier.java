package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.notification.NotificationDispatchService;
import org.springframework.stereotype.Component;

@Component
class ReviewExecutionNotifier {

    private final NotificationDispatchService notificationDispatchService;

    ReviewExecutionNotifier(NotificationDispatchService notificationDispatchService) {
        this.notificationDispatchService = notificationDispatchService;
    }

    void reviewFinished(ReviewTask task, int findingCount) {
        if (notificationDispatchService != null) {
            notificationDispatchService.reviewFinished(task, findingCount);
        }
    }

    void reviewFailed(ReviewTask task) {
        if (notificationDispatchService != null) {
            notificationDispatchService.reviewFailed(task);
        }
    }
}
