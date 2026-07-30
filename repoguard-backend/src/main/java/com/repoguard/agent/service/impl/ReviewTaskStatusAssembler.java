package com.repoguard.agent.service.impl;

import com.repoguard.agent.dto.ReviewTaskListItem;
import com.repoguard.agent.dto.ReviewTaskStatusResponse;
import com.repoguard.agent.dto.ReviewTimelineItem;
import com.repoguard.agent.entity.ReviewTask;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

@Component
public class ReviewTaskStatusAssembler {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public ReviewTaskStatusResponse assemble(
        ReviewTask task,
        ReviewTaskListItem item,
        ReviewTimelineItem latestTimeline
    ) {
        return new ReviewTaskStatusResponse(
            item.id(),
            item.status(),
            item.riskLevel(),
            item.llmStatus(),
            item.duration(),
            formatDateTimeOrNull(resolveTaskUpdatedAt(task)),
            item.failureCategory(),
            item.failureReason(),
            item.failureSuggestion(),
            latestTimeline,
            item.humanReviewRequired(),
            item.humanReviewStatus(),
            item.humanReviewNote(),
            item.humanReviewBy(),
            item.humanReviewedAt(),
            item.assessmentStatus()
        );
    }

    private LocalDateTime resolveTaskUpdatedAt(ReviewTask task) {
        if (task.getFinishedAt() != null) {
            return task.getFinishedAt();
        }
        if (task.getStartedAt() != null) {
            return task.getStartedAt();
        }
        return task.getCreatedAt();
    }

    private String formatDateTimeOrNull(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME_FORMATTER);
    }
}
