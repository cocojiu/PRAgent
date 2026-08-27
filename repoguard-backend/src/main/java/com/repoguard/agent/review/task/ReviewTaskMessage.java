package com.repoguard.agent.review.task;

import com.repoguard.agent.observability.ReviewTaskLogContextValues;
import com.repoguard.agent.tenancy.TenantContext;
import java.time.LocalDateTime;

public record ReviewTaskMessage(
    Long taskId,
    String organization,
    String repository,
    Integer prNumber,
    String commit,
    LocalDateTime queuedAt,
    String traceId,
    Integer priority,
    Long tenantId
) implements ReviewTaskLogContextValues {

    public ReviewTaskMessage(
        Long taskId,
        String organization,
        String repository,
        Integer prNumber,
        String commit,
        LocalDateTime queuedAt,
        String traceId,
        Integer priority
    ) {
        this(
            taskId,
            organization,
            repository,
            prNumber,
            commit,
            queuedAt,
            traceId,
            priority,
            TenantContext.currentTenantId()
        );
    }

    public ReviewTaskMessage(
        Long taskId,
        String organization,
        String repository,
        Integer prNumber,
        String commit,
        LocalDateTime queuedAt,
        String traceId
    ) {
        this(taskId, organization, repository, prNumber, commit, queuedAt, traceId, 4);
    }

    public ReviewTaskMessage(
        Long taskId,
        String organization,
        String repository,
        Integer prNumber,
        String commit,
        LocalDateTime queuedAt
    ) {
        this(taskId, organization, repository, prNumber, commit, queuedAt, null, 4);
    }

    public int normalizedPriority() {
        return Math.max(0, Math.min(priority == null ? 4 : priority, 10));
    }
}
