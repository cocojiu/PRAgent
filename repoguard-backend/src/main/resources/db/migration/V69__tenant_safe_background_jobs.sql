-- Tenant-safe background control records. This migration is intentionally
-- forward-only so already validated V68 checksums remain immutable.
alter table operational_data_cleanup_audit
    add column tenant_id bigint null after id,
    add key idx_operational_cleanup_tenant_created (tenant_id, created_at, id);

alter table secret_re_encryption_job
    add column tenant_id bigint not null default 1 after id,
    drop index uk_secret_re_encryption_active_slot,
    add unique key uk_secret_re_encryption_tenant_active_slot (tenant_id, active_slot),
    add unique key uk_secret_re_encryption_tenant_id (tenant_id, id),
    add key idx_secret_re_encryption_tenant_due (tenant_id, status, next_retry_at, lease_until, id);

alter table secret_re_encryption_job_item
    drop foreign key fk_secret_re_encryption_job_item_job,
    add column tenant_id bigint not null default 1 after id,
    add key idx_secret_re_encryption_item_tenant_job (tenant_id, job_id, id),
    add constraint fk_secret_re_encryption_item_tenant_job
        foreign key (tenant_id, job_id)
        references secret_re_encryption_job (tenant_id, id)
        on delete cascade;
