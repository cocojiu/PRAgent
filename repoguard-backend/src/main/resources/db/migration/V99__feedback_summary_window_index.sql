alter table review_finding
    add key idx_finding_tenant_feedback_window (tenant_id, feedback_at, id);
