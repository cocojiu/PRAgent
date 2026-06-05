package com.repoguard.agent.messaging;

import java.time.LocalDateTime;

public record ReviewTaskMessage(
    Long taskId,
    String organization,
    String repository,
    Integer prNumber,
    String commit,
    LocalDateTime queuedAt
) {
}
