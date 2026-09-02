-- Additive lifecycle metadata for aggregate-only evaluation reports.
-- Reports remain soft-deletable so historical release evidence stays traceable.
alter table llm_evaluation_report
    add column lifecycle_status varchar(24) not null default 'ACTIVE' after status,
    add column retention_days int not null default 180 after lifecycle_status,
    add column expires_at datetime(6) null after retention_days,
    add column authorization_revoked_at datetime(6) null after expires_at,
    add column authorization_revocation_reason varchar(512) null after authorization_revoked_at,
    add column frozen_at datetime(6) null after authorization_revocation_reason,
    add column deleted_at datetime(6) null after frozen_at,
    add column lifecycle_version bigint not null default 0 after deleted_at,
    add column updated_at datetime(6) null after created_at,
    add key idx_llm_evaluation_report_lifecycle_expiry (tenant_id, lifecycle_status, expires_at, id),
    add key idx_llm_evaluation_report_lifecycle_updated (tenant_id, updated_at, id),
    add constraint chk_llm_evaluation_report_lifecycle_status
        check (lifecycle_status in ('ACTIVE', 'EXPIRED', 'FROZEN', 'AUTHORIZATION_REVOKED', 'DELETED')),
    add constraint chk_llm_evaluation_report_retention_days
        check (retention_days between 30 and 3650),
    add constraint chk_llm_evaluation_report_lifecycle_version
        check (lifecycle_version >= 0);

create table llm_evaluation_report_lifecycle_audit (
    id bigint not null auto_increment,
    tenant_id bigint not null,
    report_id bigint not null,
    operation_key varchar(128) not null,
    action varchar(32) not null,
    from_status varchar(24) null,
    to_status varchar(24) not null,
    operator varchar(128) not null,
    second_approver varchar(128) null,
    reason varchar(512) not null default '',
    status varchar(16) not null default 'COMPLETED',
    failure_code varchar(64) null,
    created_at datetime(6) not null default current_timestamp(6),
    updated_at datetime(6) not null default current_timestamp(6),
    primary key (id),
    unique key uk_llm_evaluation_report_lifecycle_audit_operation (tenant_id, operation_key),
    unique key uk_llm_evaluation_report_lifecycle_audit_id (tenant_id, id),
    key idx_llm_evaluation_report_lifecycle_audit_report (tenant_id, report_id, created_at, id),
    constraint fk_llm_evaluation_report_lifecycle_audit_tenant
        foreign key (tenant_id) references tenant(id),
    constraint fk_llm_evaluation_report_lifecycle_audit_report
        foreign key (tenant_id, report_id)
        references llm_evaluation_report(tenant_id, id),
    constraint chk_llm_evaluation_report_lifecycle_audit_action
        check (action in ('FREEZE', 'REVOKE_AUTHORIZATION', 'DELETE', 'DELETE_BLOCKED_FREEZE', 'EXPIRE', 'EXPORT')),
    constraint chk_llm_evaluation_report_lifecycle_audit_from_status
        check (from_status is null or from_status in ('ACTIVE', 'EXPIRED', 'FROZEN', 'AUTHORIZATION_REVOKED', 'DELETED')),
    constraint chk_llm_evaluation_report_lifecycle_audit_to_status
        check (to_status in ('ACTIVE', 'EXPIRED', 'FROZEN', 'AUTHORIZATION_REVOKED', 'DELETED')),
    constraint chk_llm_evaluation_report_lifecycle_audit_status
        check (status in ('COMPLETED', 'FAILED'))
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_unicode_ci;
