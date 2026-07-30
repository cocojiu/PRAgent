package com.repoguard.agent.review.task;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.timeline.ReviewTimelineStatus;
import java.time.LocalDateTime;

public interface ReviewTaskPublishFailureStore {

    boolean markDirectPublishFailed(
        ReviewTask task,
        ReviewTaskPublishException exception,
        LocalDateTime failedAt,
        ReviewTaskDirectPublishFailurePolicy policy
    );

    void markCurrentTimelinesDone(Long taskId);

    void appendTimeline(Long taskId, String label, LocalDateTime eventTime, ReviewTimelineStatus status);
}
