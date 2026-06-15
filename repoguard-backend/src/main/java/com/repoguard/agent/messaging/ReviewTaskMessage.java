package com.repoguard.agent.messaging;

import java.time.LocalDateTime;

public record ReviewTaskMessage(
    Long taskId,
    String organization,
    String repository,
    Integer prNumber,
    String commit,
    LocalDateTime queuedAt,
    String traceId
) {

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
