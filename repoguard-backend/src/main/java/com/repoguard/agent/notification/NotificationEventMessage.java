package com.repoguard.agent.notification;

import com.repoguard.agent.tenancy.TenantContext;

public record NotificationEventMessage(
    Long eventId,
    String eventKey,
    String eventType,
    Long taskId,
    Long batchId,
    Long tenantId
) {

    public NotificationEventMessage(
        Long eventId,
        String eventKey,
        String eventType,
        Long taskId,
        Long batchId
    ) {
        this(eventId, eventKey, eventType, taskId, batchId, TenantContext.currentTenantId());
    }
}
