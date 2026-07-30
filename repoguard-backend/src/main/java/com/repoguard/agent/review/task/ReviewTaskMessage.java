package com.repoguard.agent.review.task;

import com.repoguard.agent.observability.ReviewTaskLogContextValues;
import java.time.LocalDateTime;

public record ReviewTaskMessage(
    Long taskId,
    String organization,
    String repository,
    Integer prNumber,
    String commit,
    LocalDateTime queuedAt,
    String traceId
) implements ReviewTaskLogContextValues {

    public ReviewTaskMessage(
        Long taskId,
        String organization,
        String repository,
        Integer prNumber,
        String commit,
        LocalDateTime queuedAt
    ) {
        this(taskId, organization, repository, prNumber, commit, queuedAt, null);
    }
}
