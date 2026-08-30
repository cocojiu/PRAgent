-- Expand phase for tenant-safe parent/child relationships. Keep the legacy
-- id-only foreign keys in place so the current and previous application
-- versions remain compatible while every composite key is built and checked.
alter table review_task
    add unique key uk_review_task_tenant_id (tenant_id, id);

alter table review_execution_attempt
    add unique key uk_review_attempt_tenant_id (tenant_id, id);

alter table review_finding
    add unique key uk_review_finding_tenant_id (tenant_id, id),
    add key idx_review_finding_tenant_attempt (tenant_id, attempt_id);

alter table github_comment_publication_batch
    add unique key uk_github_comment_batch_tenant_id (tenant_id, id);

alter table notification_event
    add unique key uk_notification_event_tenant_id (tenant_id, id);

alter table notification_channel_binding
    add unique key uk_notification_binding_tenant_id (tenant_id, id);

alter table review_rule_policy_snapshot
    add unique key uk_rule_policy_snapshot_tenant_id (tenant_id, id);

alter table review_strategy_policy_snapshot
    add unique key uk_strategy_policy_snapshot_tenant_id (tenant_id, id),
    add key idx_strategy_policy_tenant_source (tenant_id, source_snapshot_id);

alter table changed_file
    add key idx_changed_file_tenant_attempt (tenant_id, attempt_id);

alter table github_comment_publication
    add key idx_github_comment_pub_tenant_finding (tenant_id, finding_id);

alter table github_comment_publication_batch_item
    add key idx_github_comment_item_tenant_task (tenant_id, task_id),
    add key idx_github_comment_item_tenant_finding (tenant_id, finding_id);

alter table notification_delivery_log
    add key idx_notification_delivery_tenant_binding (tenant_id, binding_id);

alter table review_policy_promotion_evidence
    add key idx_policy_evidence_tenant_rule_snapshot (tenant_id, rule_policy_snapshot_id),
    add key idx_policy_evidence_tenant_strategy_snapshot (tenant_id, strategy_policy_snapshot_id);
