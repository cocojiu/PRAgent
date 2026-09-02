-- Tenant-scoped drift detection and manually confirmed repair evidence.
-- The preview is immutable input for a repair; no source, prompt, provider payload or secret is stored.
create table llm_model_release_drift_audit (
    id bigint not null auto_increment,
    tenant_id bigint not null,
    operation_key varchar(128) not null,
    preview_fingerprint char(64) not null,
    status varchar(16) not null default 'PREVIEW',
    operator varchar(128) not null,
    before_json longtext not null,
    after_json longtext null,
    changed_release_count int unsigned not null default 0,
    changed_task_count int unsigned not null default 0,
    skipped_running_task_count int unsigned not null default 0,
    failure_code varchar(64) null,
    created_at datetime(6) not null default current_timestamp(6),
    updated_at datetime(6) not null default current_timestamp(6),
    primary key (id),
    unique key uk_llm_model_release_drift_audit_operation (tenant_id, operation_key),
    unique key uk_llm_model_release_drift_audit_id (tenant_id, id),
    key idx_llm_model_release_drift_audit_status (tenant_id, status, updated_at),
    key idx_llm_model_release_drift_audit_fingerprint (tenant_id, preview_fingerprint),
    constraint fk_llm_model_release_drift_audit_tenant
        foreign key (tenant_id) references tenant(id),
    constraint chk_llm_model_release_drift_audit_fingerprint
        check (preview_fingerprint regexp '^[0-9a-fA-F]{64}$'),
    constraint chk_llm_model_release_drift_audit_status
        check (status in ('PREVIEW', 'RUNNING', 'COMPLETED', 'FAILED')),
    constraint chk_llm_model_release_drift_audit_counts
        check (changed_release_count >= 0 and changed_task_count >= 0 and skipped_running_task_count >= 0)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
