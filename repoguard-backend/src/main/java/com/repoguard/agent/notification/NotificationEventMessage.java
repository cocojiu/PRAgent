package com.repoguard.agent.notification;

import com.repoguard.agent.tenancy.TenantContext;
import com.repoguard.agent.observability.LogContext;

public record NotificationEventMessage(
    Long eventId,
    String eventKey,
    String eventType,
    Long taskId,
    Long batchId,
    Long tenantId,
    String traceId
) {

    public NotificationEventMessage(
        Long eventId,
        String eventKey,
        String eventType,
        Long taskId,
        Long batchId,
        Long tenantId
    ) {
        this(eventId, eventKey, eventType, taskId, batchId, tenantId, LogContext.currentTraceId());
    }

    public NotificationEventMessage(
        Long eventId,
        String eventKey,
        String eventType,
        Long taskId,
        Long batchId
    ) {
        this(
            eventId,
            eventKey,
            eventType,
            taskId,
            batchId,
            TenantContext.currentTenantId(),
            LogContext.currentTraceId()
        );
    }
}
