package com.repoguard.agent.dto;

import com.repoguard.agent.entity.ReviewTask;
import java.time.LocalDateTime;
import java.util.Objects;

/** Internal optimistic assignment context, captured by the server rather than accepted from an HTTP caller. */
public record ReviewAssignmentSnapshot(Long attemptId, String headSha, Long generation, String assignee,
                                      LocalDateTime assignedAt, LocalDateTime deadline) {
    public static ReviewAssignmentSnapshot from(ReviewTask task) {
        return new ReviewAssignmentSnapshot(task.getCurrentAttemptId(), task.getCommitSha(), task.getGeneration(),
            task.getReviewAssignee(), task.getReviewAssignedAt(), task.getReviewSlaDeadline());
    }
    public boolean matches(ReviewTask task) { return Objects.equals(this, from(task)); }
}
