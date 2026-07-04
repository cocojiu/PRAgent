package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.notification.NotificationDispatchService;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class ReviewExecutionNotifier {

    private final NotificationDispatchService notificationDispatchService;

    ReviewExecutionNotifier(NotificationDispatchService notificationDispatchService) {
        this.notificationDispatchService = Objects.requireNonNull(
            notificationDispatchService,
            "notificationDispatchService"
        );
    }

    void reviewFinished(ReviewTask task, int findingCount) {
        notificationDispatchService.reviewFinished(task, findingCount);
    }

    void reviewFailed(ReviewTask task) {
        notificationDispatchService.reviewFailed(task);
    }
}
