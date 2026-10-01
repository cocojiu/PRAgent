-- Scope complete high-risk notification candidates before ordering recent tasks.
alter table review_task
    add key idx_review_task_tenant_notification_risk
        (tenant_id, assessment_status, risk_level, created_at, id),
    algorithm=inplace,
    lock=none;
